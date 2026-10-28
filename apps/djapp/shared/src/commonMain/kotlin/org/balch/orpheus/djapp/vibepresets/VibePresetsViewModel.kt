package org.balch.orpheus.djapp.vibepresets

import androidx.compose.runtime.Composable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.VibePresetSlot
import org.balch.orpheus.djapp.DjBuildKonfig
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.mixer.TrackGainSink
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Per-vibe saved mixes. Each vibe load retakes the baseline (the "vibe's original") and, when the
 * vibe has a selected preset, writes it over the vibe's values from inside `applyVibe`.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(VibePresetsFeature::class, startup = true)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<VibePresetsFeature>())
class VibePresetsViewModel(
    private val pulsar: PulsarFeature,
    synthController: SynthController,
    trackGainSink: TrackGainSink,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val availability: VibePresetsAvailability,
    private val scope: FeatureCoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val clock: PresetClock = PresetClock { Clock.System.now().toEpochMilliseconds() },
) : VibePresetsFeature {

    private val log = logging("VibePresetsVM")

    private val ports = VibePresetPorts(
        synth = synthController,
        pulsar = pulsar,
        tracks = trackGainSink,
        captureTracks = DjBuildKonfig.SHOW_TUNING,
    )

    // Read from the listener on whatever thread runs applyVibe, so state lives in StateFlows.
    private val book = MutableStateFlow<Map<String, VibePresetSlot>>(emptyMap())
    private val barShown = MutableStateFlow(false)
    private val baseline = MutableStateFlow<Map<String, Float>?>(null)
    private val draft = MutableStateFlow<CaptureDraft?>(null)
    private val tuning = MutableStateFlow(TrackTuning())

    // The listener and the late-load apply are both synchronous, so a plain lock serialises them;
    // listenedLoad lets the late path tell an apply still on its way to the listener from a finished one.
    private val applyLock = SynchronizedObject()

    /** The vibeLoadCount a preset was last written for, so the late store load doesn't write it twice. */
    @Volatile private var appliedLoad: Int? = null

    /** The vibeLoadCount the listener last ran for; null until it first runs. */
    @Volatile private var listenedLoad: Int? = null

    /** The vibeLoadCount when the listener was registered; loads up to it never reach the listener. */
    private var registeredLoad = 0

    /** The vibe the baseline was taken for. */
    @Volatile private var baselineVibe: String? = null

    /**
     * True once a preset has been written over the baseline. A same-vibe re-apply leaves Horn, DJ and the
     * mix where they are, so retaking the baseline then would make the preset the "original".
     */
    @Volatile private var presetOverBaseline = false

    /** Book saves wait for the init load, so a partial book never overwrites the stored one. */
    @Volatile private var storeLoaded = false

    /** The vibe the open draft was built for; a load of another vibe drops the draft. */
    @Volatile private var draftVibe: String? = null

    override val actions = VibePresetsActions(
        setBarShown = ::onSetBarShown,
        openCapture = ::onOpenCapture,
        cancelCapture = { draft.value = null },
        saveCapture = ::onSaveCapture,
        select = ::onSelect,
        loadOriginal = ::onLoadOriginal,
        rename = ::onRename,
        delete = { id -> updateSlot(pulsar.vibeFlow.value.name) { it.delete(id) } },
    )

    private data class Inputs(
        val book: Map<String, VibePresetSlot>,
        val barShown: Boolean,
        val vibe: Vibe,
        val baseline: Map<String, Float>?,
        val draft: CaptureDraft?,
    )

    override val stateFlow: StateFlow<VibePresetsUiState> =
        if (!availability.isAvailable()) {
            MutableStateFlow(VibePresetsUiState())
        } else {
            combine(
                combine(book, barShown, pulsar.vibeFlow, baseline, draft, ::Inputs),
                tuning,
                // A trigger only: it also fires for section tempo moves, so changed comes from a compare.
                ports.changes.onStart { emit(Unit) },
            ) { inputs, tuning, _ -> project(inputs, tuning) }
                .stateIn(scope, SynthFeature.sharingStrategy, VibePresetsUiState())
        }

    init {
        if (availability.isAvailable()) {
            scope.launch { trackGainSink.state.collect { tuning.value = it } }
            registeredLoad = pulsar.vibeLoadCount.value
            pulsar.addVibeAppliedListener(::onVibeApplied)
            scope.launch(dispatcherProvider.io) {
                val prefs = appPreferencesRepository.load()
                // Keep any edit made before the load finished, and save it now that the book is whole.
                book.update { prefs.vibePresets + it }
                barShown.value = prefs.vibePresetBarShown
                storeLoaded = true
                if (book.value != prefs.vibePresets) persistBook()
                applyAfterLateLoad()
            }
        }
    }

    private fun project(inputs: Inputs, tuning: TrackTuning): VibePresetsUiState {
        val slot = inputs.book[inputs.vibe.name] ?: VibePresetSlot()
        val selected = slot.selected
        val reference = selected?.values ?: inputs.baseline
        val changed = reference != null && changesBetween(reference, ports.read(tuning)).isNotEmpty()
        return VibePresetsUiState(
            available = true,
            barShown = inputs.barShown,
            vibeName = inputs.vibe.name,
            presets = slot.presets.map { PresetRow(it.id, it.name, it.capturedAtMs) },
            selectedId = selected?.id,
            changed = changed,
            draft = inputs.draft,
        )
    }

    // Runs inside applyVibe: a throw here would stop the vibe loading. A same-vibe re-apply (an
    // Android engine rebuild, say) writes the selected preset again over unsaved tweaks, by design.
    private fun onVibeApplied(vibe: Vibe): Boolean = runCatching {
        synchronized(applyLock) {
            val load = pulsar.vibeLoadCount.value
            // Keep the baseline when a late load already took it for this load, or a preset sits on this vibe's.
            val keep = appliedLoad == load || (vibe.name == baselineVibe && presetOverBaseline)
            if (!keep) takeBaseline(vibe.name)
            listenedLoad = load
            if (vibe.name != draftVibe) draft.value = null
            val preset = book.value[vibe.name]?.selected ?: return@synchronized false
            ports.write(preset.values)
            presetOverBaseline = true
            appliedLoad = load
            true
        }
    }.getOrElse { e ->
        log.warn(e) { "Preset apply failed for ${vibe.name}" }
        false
    }

    /** The first applyVibe can beat the prefs load; the selected preset still wins once it arrives. */
    private fun applyAfterLateLoad(): Unit = synchronized(applyLock) {
        val load = pulsar.vibeLoadCount.value
        // At 0 vibeFlow is still the catalog's first vibe, not the restored one; the listener takes the first load.
        if (load == 0 || appliedLoad == load) return@synchronized
        val listenerRan = listenedLoad == load
        // Otherwise an apply has bumped the count and is on its way to the listener, which will apply it.
        val missedBeforeRegistration = listenedLoad == null && load == registeredLoad
        if (!listenerRan && !missedBeforeRegistration) return@synchronized
        val vibe = pulsar.vibeFlow.value.name
        val preset = book.value[vibe]?.selected ?: return@synchronized
        if (!listenerRan) takeBaseline(vibe)
        ports.write(preset.values)
        presetOverBaseline = true
        appliedLoad = load
    }

    private fun takeBaseline(vibe: String) {
        baseline.value = ports.read(TrackTuning())
        baselineVibe = vibe
        presetOverBaseline = false
    }

    private fun current(): Map<String, Float> = ports.read(tuning.value)

    private fun onOpenCapture() {
        val vibe = pulsar.vibeFlow.value.name
        val slot = book.value[vibe] ?: VibePresetSlot()
        val current = current()
        val changes = changesBetween(baseline.value ?: current, current)
        draftVibe = vibe
        draft.value = CaptureDraft(
            suggestedName = suggestPresetName(changes, slot.presets.map { it.name }.toSet()),
            chips = changes.map { it.chipText() },
            count = minOf(slot.presets.size + 1, MAX_PRESETS),
            full = slot.isFull,
            replaceDefaultId = if (slot.isFull) slot.oldestId() else null,
        )
    }

    private fun onSaveCapture(name: String, replaceId: Int?) {
        val vibe = pulsar.vibeFlow.value.name
        val current = current()
        val base = baseline.value ?: current
        val changes = changesBetween(base, current)
        val changedKeys = changes.map { it.field.key }.toSet()
        val suggested = draft.value?.suggestedName
        fun VibePresetSlot.replaceFor(): Int? = replaceId?.takeIf { id -> presets.any { it.id == id } }
        // A full book needs a preset the user picked to replace; never drop one silently.
        val before = book.value[vibe] ?: VibePresetSlot()
        if (before.isFull && before.replaceFor() == null) return
        updateSlot(vibe) { slot ->
            val replace = slot.replaceFor()
            if (slot.isFull && replace == null) return@updateSlot slot
            slot.capture(
                name = name.trim().ifEmpty {
                    suggested ?: suggestPresetName(changes, slot.presets.map { it.name }.toSet())
                },
                atMs = clock.nowMs(),
                values = current,
                originals = base.filterKeys { it in changedKeys },
                replaceId = replace,
            )
        }
        draft.value = null
    }

    private fun onSelect(id: Int) {
        val vibe = pulsar.vibeFlow.value.name
        val preset = book.value[vibe]?.presets?.firstOrNull { it.id == id } ?: return
        synchronized(applyLock) {
            ports.write(preset.values)
            presetOverBaseline = true
        }
        updateSlot(vibe) { it.select(id) }
    }

    private fun onLoadOriginal() {
        synchronized(applyLock) {
            baseline.value?.let(ports::write)
            presetOverBaseline = false
        }
        updateSlot(pulsar.vibeFlow.value.name) { it.select(null) }
    }

    private fun onRename(id: Int, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        updateSlot(pulsar.vibeFlow.value.name) { it.rename(id, trimmed) }
    }

    private fun onSetBarShown(shown: Boolean) {
        barShown.value = shown
        savePrefs("bar visibility") { it.copy(vibePresetBarShown = shown) }
    }

    private fun updateSlot(vibe: String, transform: (VibePresetSlot) -> VibePresetSlot) {
        book.update { it + (vibe to transform(it[vibe] ?: VibePresetSlot())) }
        if (storeLoaded) persistBook()
    }

    // Reads the book at write time, so out-of-order saves still end on the latest.
    private fun persistBook() = savePrefs("presets") { it.copy(vibePresets = book.value) }

    // A failed encode or write is logged, never thrown: it would take down the feature scope.
    private fun savePrefs(what: String, transform: (AppPreferences) -> AppPreferences) {
        scope.launch(dispatcherProvider.io) {
            runCatching { appPreferencesRepository.update(transform) }.onFailure { e ->
                if (e is CancellationException) throw e
                log.warn(e) { "Could not save vibe preset $what" }
            }
        }
    }

    private fun VibePresetSlot.oldestId(): Int? = presets.minByOrNull { it.capturedAtMs }?.id

    companion object {
        fun previewFeature(state: VibePresetsUiState = VibePresetsUiState()): VibePresetsFeature =
            object : VibePresetsFeature {
                override val stateFlow: StateFlow<VibePresetsUiState> = MutableStateFlow(state)
                override val actions: VibePresetsActions = VibePresetsActions.EMPTY
            }

        @Composable
        fun feature(): VibePresetsFeature = synthFeature<VibePresetsFeature>()
    }
}
