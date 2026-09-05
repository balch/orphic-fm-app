package org.balch.orpheus.features.pulsar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.engagement.EngagementAction
import org.balch.orpheus.core.engagement.EngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.AppSymbol
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.anonmalies.CrossfadeAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.CutAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.FilterAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.ScratchAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.StormAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.SwellAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.TapeAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.DuckingProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.NotatedScore
import org.balch.orpheus.features.pulsar.models.NotatedScoreProvider
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.PitchEvolution
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.ScratchEffect
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.SoloBehavior
import org.balch.orpheus.features.pulsar.models.SoloMarkovConfig
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.StrikeEffect
import org.balch.orpheus.features.pulsar.models.TapeStopEffect
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.TransitionEffect
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.WahParams
import org.balch.orpheus.features.pulsar.models.chordComping
import org.balch.orpheus.features.pulsar.models.chordFollow
import org.balch.orpheus.features.pulsar.models.lickDegreeOffset
import org.balch.orpheus.features.pulsar.models.lickMode
import org.balch.orpheus.features.pulsar.playback.PulsarTransitionRunner
import org.balch.orpheus.features.pulsar.playback.SongEndingEventSource
import org.balch.orpheus.features.pulsar.playback.SongEndingPreferences
import org.balch.orpheus.features.pulsar.playback.TransitionPreferences
import org.balch.orpheus.features.pulsar.vibes.VibeCatalog
import org.balch.orpheus.features.pulsar.vibes.VibeCatalogPolicy
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Serializable
@Immutable
data class PulsarUiState(
    val playing: Boolean = false,
    val globalPaused: Boolean = true,
    val bpm: Float = 128f,
    val vibeName: String = "",
    val vibe: Vibe,
    /**
     * Id of the notated score currently driving a track, if any. Persisted alongside
     * [vibeName]: the written part is not in the port map, so restoring the vibe alone
     * would silently hand the score's track back to the pattern generator.
     */
    val notatedScoreId: String? = null,
    val energy: Float = 0.5f,
    val complexity: Float = 0.3f,
    val space: Float = 0.4f,
    val mood: Float = 0.5f,
    val deep: Float = 0.0f,
    val rootNote: Int = 2,
    val scaleIndex: Int = 0,
    val mix: Float = 1.0f,
    // Console-fader convention: stored 0..1 is fader travel. The C++ law
    // maps 0.75 travel → unity (1×). Defaults to unity so a fresh load plays
    // at the vibe's intrinsic balance — pull down to cut, push up to boost.
    val percMix: Float = 0.75f,
    val envelopeMode: Int = 0,  // 0=AD, 1=Tides, 2=Blend (energy-driven)
    val selectedTrack: Int? = null,
    val trackEnginesEdm: List<Int> = listOf(21, 22, 23, 9, 14, 14, 17, 20),
    val trackEnginesSpace: List<Int> = listOf(20, 17, 23, 19, 6, 14, 17, 19),
    val trackMuted: List<Boolean> = List(8) { false },
)

@Immutable
data class PulsarPanelActions(
    val setVibe: (Vibe) -> Unit = {},
    val setEnergy: (Float) -> Unit = {},
    val setComplexity: (Float) -> Unit = {},
    val setSpace: (Float) -> Unit = {},
    val setMood: (Float) -> Unit = {},
    val setBpm: (Float) -> Unit = {},
    val setDeep: (Float) -> Unit = {},
    val setRootNote: (Int) -> Unit = {},
    val setScale: (Int) -> Unit = {},
    val setMix: (Float) -> Unit = {},
    val setPercMix: (Float) -> Unit = {},
    val setEnvelopeMode: (Int) -> Unit = {},
    val selectTrack: (Int?) -> Unit = {},
    val setTrackEngineEdm: (Int, Int) -> Unit = { _, _ -> },
    val setTrackEngineSpace: (Int, Int) -> Unit = { _, _ -> },
    val toggleTrackMute: (Int) -> Unit = {},
    val songEndingEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    val onSetSongEndingEnabled: (Boolean) -> Unit = {},
    val transitionSpec: StateFlow<TransitionSpec> = MutableStateFlow(TransitionStyle.default),
    val onSetTransitionStyle: (TransitionStyle) -> Unit = {},
    val onSetTransitionHandoffMs: (Int) -> Unit = {},
    val onSetTransitionRandomPool: (List<TransitionStyle>) -> Unit = {},
    val onOpenTransitionSettings: () -> Unit = {},
    /** Currently-running transition style, or null when no transition is in flight. */
    val activeTransition: StateFlow<TransitionStyle?> = MutableStateFlow(null),
    /**
     * The transition style that will fire for the current vibe. For RANDOM,
     * this is the pre-rolled substyle from [SongEndingEventSource] — never
     * RANDOM itself. Used by the step-grid final-section suffix so the user
     * sees what the random roll actually picked. The pill itself stays on the
     * user's configured style (e.g., shows "RANDOM" when RANDOM is selected).
     */
    val resolvedTransitionStyle: StateFlow<TransitionStyle> =
        MutableStateFlow(TransitionStyle.FADE),
    /**
     * Index of the song's final section once the outro has been captured, or
     * `-1` when no outro is pending. The step-grid status overlay uses this
     * to suffix the section description with the upcoming transition style
     * while the final section is playing.
     */
    val finalSectionIndex: StateFlow<Int> = MutableStateFlow(-1),
    /**
     * True from the moment the outro is armed (auto-trigger or manual long-press)
     * until the song ends and state resets on the next vibe change. The pill
     * uses this to highlight the armed state.
     */
    val outroArmed: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * Long-press handler — arms the outro immediately. Equivalent to the
     * auto-trigger firing now, regardless of playing time or random roll.
     */
    val onArmOutro: () -> Unit = {},
    /**
     * Manual trigger handler for the VIBE dropdown — fires the Anomaly
     * Engine, bumping the request counter the C++ engine edge-detects on.
     */
    val onTriggerAnomaly: () -> Unit = {},
    /**
     * True for a short window after [onTriggerAnomaly] fires. Unlike
     * [outroArmed] (which latches until the song ends), the Void Anomaly is
     * transient, so this auto-clears on a timer — see
     * [PulsarViewModel.ANOMALY_HIGHLIGHT]. The VIBE dropdown uses this to
     * tint itself as trigger confirmation.
     */
    val anomalyArmed: StateFlow<Boolean> = MutableStateFlow(false),
) {
    companion object {
        val EMPTY = PulsarPanelActions()
    }
}

/** User intents for the Pulsar panel. */
private sealed interface PulsarIntent {
    data class Playing(val value: Boolean) : PulsarIntent
    data class GlobalPaused(val value: Boolean) : PulsarIntent
    data class VibeChange(val value: Vibe) : PulsarIntent
    data class NotatedScore(val value: String?) : PulsarIntent
    data class Energy(val value: Float) : PulsarIntent
    data class Complexity(val value: Float) : PulsarIntent
    data class Space(val value: Float) : PulsarIntent
    data class Mood(val value: Float) : PulsarIntent
    data class Bpm(val value: Float) : PulsarIntent
    data class Deep(val value: Float) : PulsarIntent
    data class RootNote(val value: Int) : PulsarIntent
    data class Scale(val value: Int) : PulsarIntent
    data class Mix(val value: Float) : PulsarIntent
    data class PercMix(val value: Float) : PulsarIntent
    data class EnvelopeMode(val value: Int) : PulsarIntent
    data class SelectedTrack(val value: Int?) : PulsarIntent
    data class TrackEngineEdm(val track: Int, val engine: Int) : PulsarIntent
    data class TrackEngineSpace(val track: Int, val engine: Int) : PulsarIntent
    data class TrackMuteList(val muteList: List<Boolean>) : PulsarIntent
}

interface PulsarFeature : SynthFeature<PulsarUiState, PulsarPanelActions> {
    val vibeList: List<Vibe>
        get() = emptyList()  // default for previews

    /**
     * Cheap, ordered list of vibe display names. Reading this never forces a
     * `Vibe` body to be constructed — useful for advancers, search, and any
     * UI that just needs labels.
     */
    val vibeNames: List<String>
        get() = emptyList()

    val vibeFlow: StateFlow<Vibe>

    fun applyVibe(vibe: Vibe)

    /**
     * Look up a vibe by display name and apply it. Only the matched provider's
     * `Vibe` body is realized — other vibes stay deferred. Returns false and
     * leaves state unchanged when the name doesn't match any vibe.
     */
    fun applyVibeByName(name: String): Boolean = false

    /**
     * Resolve [id] against the registered [NotatedScoreProvider]s and push its score, or
     * clear the previously-pushed one when [id] is `null` or matches nothing. A plain
     * provider-push could only ever say "load this" -- it had no way to say "nothing plays
     * here now," and the C++ side doesn't clear `score_driven` flags on its own: `load_vibe`
     * resets the score clock and cursors but leaves the previous vibe's flags and event
     * array exactly as they were last pushed. Fire-and-forget on the feature's own coroutine
     * scope -- callers need no coroutine of their own, since the asset read is `suspend` (a
     * bundled resource, not a hardcoded value). A no-op default so preview/stub
     * `PulsarFeature` implementations don't need to implement it.
     */
    fun applyNotatedScore(id: String?) {}

    /** Last score pushed by [applyNotatedScore]/`pushNotatedScore`, or `null` when none is driven. */
    val notatedScoreFlow: StateFlow<NotatedScore?>
        get() = MutableStateFlow(null)

    /** Live write for the orchestration panel and the color hand. field matches the wire name. */
    fun setScorePartControl(track: Int, field: String, value: Float) {}

    /** Mutes the generative tracks while a GATED section waits on the conductor. */
    fun setBandHold(held: Boolean) {}

    val arrangementStateFlow: StateFlow<PulsarArrangementState>

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.PULSAR
            override val title = "Pulsar"

            override val markdown = """
                Beat machine with vibe-based presets. Controls energy, complexity, space, and mood
                to shape rhythmic patterns generated by the Pulsar engine.

                ## Controls
                - **PLAY**: Start/stop the beat machine.
                - **BPM**: Tempo in beats per minute.
                - **VIBE**: Select a vibe preset (pushes full recipe to engine).
                - **ENERGY**: Intensity/velocity of the pattern.
                - **COMPLEXITY**: Rhythmic density and variation.
                - **SPACE**: Stereo width and reverb amount.
                - **MOOD**: Tonal character of the drum sounds.
                - **DEEP**: Overall send level to delay/reverb effects.
                - **BALANCE**: Slider to blend between delay (left) and reverb (right).
            """.trimIndent()

            override val portControlKeys = mapOf(
                PulsarSymbol.PLAYING.controlId.key to "Start/stop playback (0=stopped, 1=playing)",
                PulsarSymbol.ENERGY.controlId.key to "Pattern intensity (0..1)",
                PulsarSymbol.COMPLEXITY.controlId.key to "Rhythmic density (0..1)",
                PulsarSymbol.SPACE.controlId.key to "Stereo width and space (0..1)",
                PulsarSymbol.MOOD.controlId.key to "Tonal character (0..1)",
                PulsarSymbol.BPM.controlId.key to "Tempo in BPM (0..300)",
                PulsarSymbol.DEEP.controlId.key to "Effect send depth (0=dry, 1=full vibe sends)",
                PulsarSymbol.ROOT_NOTE.controlId.key to "Root note (0=C, 11=B)",
                PulsarSymbol.SCALE.controlId.key to "Scale index (0-5)",
                PulsarSymbol.MIX.controlId.key to "Output mix level (0..1)",
            )
        }
    }
}

/**
 * Wire-format constants for the `trans_fx_data_$i` bank (168 = [MAX_ROWS] * [ROW_FIELDS]).
 * Mirrors `TransFxType`/`kTransFxRowFields`/`kMaxTransFxRows` in
 * `liborpheus_dsp/src/pulsar_transition_fx.h` verbatim — pinned by
 * `PulsarSectionLimitsTest` so the two sides can't drift.
 */
internal object TransitionFxWire {
    const val TYPE_NONE = 0
    const val TYPE_SCRATCH = 1
    const val TYPE_TAPE_STOP = 2
    const val TYPE_STRIKE = 3
    const val ROW_FIELDS = 7
    const val MAX_ROWS = 24
    const val BANK_SIZE = ROW_FIELDS * MAX_ROWS

    /** `edge_idx` for a `Section.exitEffects` row: C++ matches it against ANY outgoing edge. */
    const val EDGE_ANY = -1f

    /** `edge_idx` for a `Section.entryEffects` row: C++ fires it on ANY arrival into the section. */
    const val EDGE_ENTRY = -2f

    /**
     * The trailing 5 of a row's 7 fields: [type_id, offset_bars, p0, p1, p2]. Scratch and
     * tape-stop always fire AT the flip (offset 0) — only StrikeEffect exposes its own offset.
     *
     * Per-type payloads, mirroring the `TransFxType` comments in `pulsar_transition_fx.h`:
     * scratch/tape-stop carry milliseconds in p0 and leave p1/p2 unused; a strike carries
     * intensity in p0, distance in p1, and its sub-bar `delayMs` — milliseconds, converted to
     * samples by `StormVoice::trigger_strike` — in p2.
     */
    fun fieldsFor(effect: TransitionEffect): FloatArray = when (effect) {
        is ScratchEffect -> floatArrayOf(TYPE_SCRATCH.toFloat(), 0f, effect.ms.toFloat(), 0f, 0f)
        is TapeStopEffect -> floatArrayOf(TYPE_TAPE_STOP.toFloat(), 0f, effect.ms.toFloat(), 0f, 0f)
        is StrikeEffect -> floatArrayOf(
            TYPE_STRIKE.toFloat(), effect.offsetBars,
            effect.intensity, effect.distance, effect.delayMs.toFloat(),
        )
    }
}

/**
 * ViewModel for the Pulsar beat machine panel.
 *
 * Bridges PulsarSymbol controls to the C++ engine via SynthController,
 * exposing a reactive PulsarUiState and stable PulsarPanelActions.
 */
// startup = true is not inferable: this registers MediaSessionManager callbacks rather than
// restoring ports, so the guard cannot see the need. Dropping it breaks lock-screen transport.
@OptIn(FlowPreview::class)
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(PulsarFeature::class, startup = true)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<PulsarFeature>())
class PulsarViewModel(
    private val synthController: SynthController,
    synthEngine: SynthEngine,
    private val pulsarSession: PulsarSession,
    private val globalTempo: GlobalTempo,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
    vibeProviders: Set<VibeProvider>,
    // Default keeps direct test construction terse; in the app graph the real
    // Set<NotatedScoreProvider> multibinding (FifthSymphonyScore today) always wins over
    // this empty default -- same "a real binding beats the default" contract
    // vibeCatalogPolicy below documents and relies on.
    notatedScoreProviders: Set<NotatedScoreProvider> = emptySet(),
    // Default keeps direct test construction terse; in the app graphs the per-platform
    // VibeCatalogPolicyProvider binding always wins (android debuggable-flag -> WIP, desktop
    // -Pcatalog level default live, iOS debug binary -> WIP, wasm live).
    vibeCatalogPolicy: VibeCatalogPolicy = VibeCatalogPolicy(),
    private val playbackMode: PulsarPlaybackMode,
    private val songEndingPreferences: SongEndingPreferences,
    private val transitionPreferences: TransitionPreferences,
    private val transitionRunner: PulsarTransitionRunner,
    private val songEndingEventSource: SongEndingEventSource,
    private val engagementTracker: EngagementTracker,
) : PulsarFeature {

    // The injected set filtered + ordered through VibeCatalog (the green-light map):
    // WIP/uncataloged vibes are curated out, catalog position = picker order, and the
    // first LIVE entry is the fresh-install default. curate() touches only the cheap
    // `name` accessor — no Vibe construction forced; vibeList below still realizes the
    // actual Vibe data only on first iteration. (Saved/user vibes will merge inside the
    // catalog when persistence lands — see VibeCatalog's KDoc hooks.)
    private val curatedProviders: List<VibeProvider> =
        VibeCatalog.curate(vibeProviders, visibleThrough = vibeCatalogPolicy.catalogLevel)

    // Lazy only in the sense that construction is deferred past PulsarViewModel's own init:
    // the first read materializes EVERY curated Vibe at once. In the app that first read is
    // PulsarPanel's `remember { pulsar.vibeList }`, which runs when the panel first composes,
    // NOT when the user opens the VIBE dropdown. So on a WIP-tier build this builds all ~47
    // Vibe bodies on the UI thread at panel-show time. If that ever shows up as startup jank,
    // the fix is to hand the picker `vibeNames` (already cheap) and resolve the Vibe on
    // selection — don't just move the `remember`, which would put the same cost on open.
    override val vibeList: List<Vibe> by lazy {
        curatedProviders.map { it.vibe }
    }

    // Cheap accessor — never forces Vibe construction.
    override val vibeNames: List<String> = curatedProviders.map { it.name }

    // Id -> provider, resolved once at construction (these instances live for the
    // ViewModel's lifetime, so a provider's own `cached` field actually caches -- unlike a
    // fresh `FifthSymphonyScore()` built per call). No catalog/curation step yet: Phase A
    // has exactly one notated score, and NotatedScoreProvider.name is deliberately its own
    // key, distinct from any VibeProvider.name (see FifthSymphonyScore's KDoc).
    private val notatedScoresById: Map<String, NotatedScoreProvider> =
        notatedScoreProviders.associateBy { it.name }

    // Id of the score last pushed, so every path that re-applies the vibe can re-push it.
    // Feeds PulsarUiState (and so the persisted blob) through the intent reducer.
    private val notatedScoreIdFlow = MutableStateFlow<String?>(null)

    // Last score actually pushed to the engine -- the orchestration panel renders parts from
    // this, and the color hand reads it to know which part it's steering live.
    private val _notatedScoreFlow = MutableStateFlow<NotatedScore?>(null)
    override val notatedScoreFlow: StateFlow<NotatedScore?> = _notatedScoreFlow.asStateFlow()

    private val log = logging("PulsarVM")

    // ═══════════════════════════════════════════════════════════
    // Control flows
    // ═══════════════════════════════════════════════════════════
    private val playingId = synthController.controlFlow(PulsarSymbol.PLAYING.controlId)
    private val mutedId = synthController.controlFlow(AppSymbol.MUTED.controlId)
    private val vibeGenerationId = synthController.controlFlow(PulsarSymbol.VIBE_GENERATION.controlId)
    private val anomalyRequestId = synthController.controlFlow(PulsarSymbol.ANOMALY_REQUEST.controlId)
    // Drives the VIBE dropdown's "armed" tint — see PulsarPanelActions.anomalyArmed
    // and the companion's ANOMALY_HIGHLIGHT constant for the auto-clear window.
    private val _anomalyArmed = MutableStateFlow(false)
    private var anomalyArmedResetJob: Job? = null
    private val energyId = synthController.controlFlow(PulsarSymbol.ENERGY.controlId)
    private val complexityId = synthController.controlFlow(PulsarSymbol.COMPLEXITY.controlId)
    private val spaceId = synthController.controlFlow(PulsarSymbol.SPACE.controlId)
    private val moodId = synthController.controlFlow(PulsarSymbol.MOOD.controlId)
    private val bpmId = synthController.controlFlow(PulsarSymbol.BPM.controlId)
    private val deepId = synthController.controlFlow(PulsarSymbol.DEEP.controlId)
    private val pulsarDelayTimeAId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_TIME_A.controlId)
    private val pulsarDelayTimeBId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_TIME_B.controlId)
    private val pulsarDelayFeedbackId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_FEEDBACK.controlId)
    private val pulsarDelayDampingId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_DAMPING.controlId)
    private val pulsarReverbSizeId = synthController.controlFlow(PulsarSymbol.PULSAR_REVERB_SIZE.controlId)
    private val pulsarReverbDampingId = synthController.controlFlow(PulsarSymbol.PULSAR_REVERB_DAMPING.controlId)
    private val pulsarReverbBrightnessId = synthController.controlFlow(PulsarSymbol.PULSAR_REVERB_BRIGHTNESS.controlId)
    private val rootNoteId = synthController.controlFlow(PulsarSymbol.ROOT_NOTE.controlId)
    private val scaleId = synthController.controlFlow(PulsarSymbol.SCALE.controlId)
    private val mixId = synthController.controlFlow(PulsarSymbol.MIX.controlId)
    private val percMixId = synthController.controlFlow(PulsarSymbol.PERC_MIX.controlId)
    private val envelopeModeId = synthController.controlFlow(PulsarSymbol.ENVELOPE_MODE.controlId)
    private val seedId = synthController.controlFlow(PulsarSymbol.SEED.controlId)
    private val lickMutationId = synthController.controlFlow(PulsarSymbol.LICK_MUTATION.controlId)
    private val lickOctaveId = synthController.controlFlow(PulsarSymbol.LICK_OCTAVE.controlId)
    private val trackEdmIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_EDM.ordinal + i * 2].controlId)
    }
    private val trackSpaceIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_SPACE.ordinal + i * 2].controlId)
    }

    // Write-only control flows for vibe recipe push
    private val trackVolumeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_VOLUME.ordinal + i].controlId)
    }
    private val trackPanIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PAN.ordinal + i].controlId)
    }
    private val trackHarmonicsIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS.ordinal + i].controlId)
    }
    private val trackTimbreIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_TIMBRE.ordinal + i].controlId)
    }
    private val trackMorphIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MORPH.ordinal + i].controlId)
    }
    private val trackPinHarmonicsIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PIN_HARMONICS.ordinal + i].controlId)
    }
    private val trackPinTimbreIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PIN_TIMBRE.ordinal + i].controlId)
    }
    private val trackPinMorphIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PIN_MORPH.ordinal + i].controlId)
    }
    private val trackHarmonicsModulationIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_MODULATION.ordinal + i].controlId)
    }
    private val trackHarmonicsMacroSourceIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_MACRO_SOURCE.ordinal + i].controlId)
    }
    private val trackHarmonicsMacroRangeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_MACRO_RANGE.ordinal + i].controlId)
    }
    private val trackEnvelopeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENVELOPE.ordinal + i].controlId)
    }
    private val trackRoleIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ROLE.ordinal + i].controlId)
    }
    private val trackBarStrategyIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_BAR_STRATEGY.ordinal + i].controlId)
    }
    private val trackEvoRhythmicIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_RHYTHMIC.ordinal + i].controlId)
    }
    private val stepCountId = synthController.controlFlow(PulsarSymbol.STEP_COUNT.controlId)
    private val trackMacroIds = (0..7).map { t ->
        (0 until PulsarSymbol.TRACK_MACRO_STRIDE).map { m ->
            synthController.controlFlow(
                PulsarSymbol.entries[
                    PulsarSymbol.TRACK_0_MACRO_ENERGY_VOL_MIN.ordinal +
                        t * PulsarSymbol.TRACK_MACRO_STRIDE + m
                ].controlId
            )
        }
    }
    private val genreDensityIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.GENRE_DENSITY_0.ordinal + i].controlId)
    }
    private val genreSwingId = synthController.controlFlow(PulsarSymbol.GENRE_SWING.controlId)
    private val genreGhostProbId = synthController.controlFlow(PulsarSymbol.GENRE_GHOST_PROB.controlId)
    private val genreNoteRangeLowId = synthController.controlFlow(PulsarSymbol.GENRE_NOTE_RANGE_LOW.controlId)
    private val genreNoteRangeHighId = synthController.controlFlow(PulsarSymbol.GENRE_NOTE_RANGE_HIGH.controlId)
    private val genreRhythmDensityId = synthController.controlFlow(PulsarSymbol.GENRE_RHYTHM_DENSITY.controlId)
    private val genreProgressionStyleId = synthController.controlFlow(PulsarSymbol.GENRE_PROGRESSION_STYLE.controlId)
    private val genreChordsPerBarId = synthController.controlFlow(PulsarSymbol.GENRE_CHORDS_PER_BAR.controlId)
    private val progressionAnchorId = synthController.controlFlow(PulsarSymbol.PROGRESSION_ANCHOR.controlId)
    private val progressionDriftRangeId = synthController.controlFlow(PulsarSymbol.PROGRESSION_DRIFT_RANGE.controlId)
    private val lickLengthId = synthController.controlFlow(PulsarSymbol.LICK_LENGTH.controlId)
    private val lickLoopLengthId = synthController.controlFlow(PulsarSymbol.LICK_LOOP_LENGTH.controlId)
    // MAX_LICK_STEPS lick steps × LICK_FIELDS_PER_STEP floats per step
    // (degree, duration, velocity, glide_rate).
    private val lickDataIds = (0 until Lick.MAX_LICK_STEPS * Lick.LICK_FIELDS_PER_STEP).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.LICK_DATA_0.ordinal + i].controlId)
    }

    // Tension control flows
    private val tensionInnerBarsId = synthController.controlFlow(PulsarSymbol.TENSION_INNER_BARS.controlId)
    private val tensionOuterBarsId = synthController.controlFlow(PulsarSymbol.TENSION_OUTER_BARS.controlId)
    private val tensionOuterDepthId = synthController.controlFlow(PulsarSymbol.TENSION_OUTER_DEPTH.controlId)
    private val tensionVolumeId = synthController.controlFlow(PulsarSymbol.TENSION_VOLUME.controlId)
    private val tensionTimingId = synthController.controlFlow(PulsarSymbol.TENSION_TIMING.controlId)
    private val tensionOctaveShiftId = synthController.controlFlow(PulsarSymbol.TENSION_OCTAVE_SHIFT.controlId)
    private val tensionKeyShiftId = synthController.controlFlow(PulsarSymbol.TENSION_KEY_SHIFT.controlId)
    private val tensionHalfLickId = synthController.controlFlow(PulsarSymbol.TENSION_HALF_LICK.controlId)
    private val tensionChromaticPassingId = synthController.controlFlow(PulsarSymbol.TENSION_CHROMATIC_PASSING.controlId)
    private val tensionEvoTimbreLowId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_TIMBRE_LOW.controlId)
    private val tensionEvoTimbreHighId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_TIMBRE_HIGH.controlId)
    private val tensionEvoTimbreProbId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_TIMBRE_PROB.controlId)
    private val tensionEvoMorphLowId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_MORPH_LOW.controlId)
    private val tensionEvoMorphHighId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_MORPH_HIGH.controlId)
    private val tensionEvoMorphProbId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_MORPH_PROB.controlId)
    private val tensionEvoHarmLowId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_HARM_LOW.controlId)
    private val tensionEvoHarmHighId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_HARM_HIGH.controlId)
    private val tensionEvoHarmProbId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_HARM_PROB.controlId)
    private val tensionEvoAttackPointId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_ATTACK_POINT.controlId)
    private val tensionEvoReleaseSpeedId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_RELEASE_SPEED.controlId)
    private val tensionSpurtChanceId = synthController.controlFlow(PulsarSymbol.TENSION_SPURT_CHANCE.controlId)
    private val trackEvoWeightIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_WEIGHT.ordinal + i].controlId)
    }
    private val trackModLfoRateIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_RATE.ordinal + i].controlId)
    }
    private val trackModLfoDepthIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_DEPTH.ordinal + i].controlId)
    }
    private val trackModLfoShapeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_SHAPE.ordinal + i].controlId)
    }
    private val trackModLfoCouplingIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_COUPLING.ordinal + i].controlId)
    }
    private val trackHoldProbabilityIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_PROBABILITY.ordinal + i].controlId)
    }
    private val trackHoldLengthMinIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MIN.ordinal + i].controlId)
    }
    private val trackHoldLengthMaxIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MAX.ordinal + i].controlId)
    }
    private val trackDelaySendIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_SEND.ordinal + i].controlId)
    }
    private val trackReverbSendIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_SEND_TRACK.ordinal + i].controlId)
    }
    private val trackNoteRangeLowIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_LOW.ordinal + it].controlId) }
    private val trackNoteRangeHighIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_HIGH.ordinal + it].controlId) }
    private val trackReverbBrightnessIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_BRIGHTNESS.ordinal + it].controlId) }
    private val trackDelayFeedbackIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_FEEDBACK_TRACK.ordinal + it].controlId) }
    private val trackGlideRateIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_GLIDE_RATE.ordinal + it].controlId) }
    private val trackLickModeIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LICK_MODE.ordinal + it].controlId) }
    private val trackLickDegreeOffsetIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LICK_DEGREE_OFFSET.ordinal + it].controlId) }
    private val trackCompingStyleIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_COMPING_STYLE.ordinal + it].controlId)
    }
    private val trackArpModeIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_MODE.ordinal + it].controlId)
    }
    private val trackArpSpeedIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_SPEED.ordinal + it].controlId)
    }
    private val trackArpDirectionIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_DIRECTION.ordinal + it].controlId)
    }
    private val trackInversionIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_INVERSION.ordinal + it].controlId)
    }
    private val trackHumanDropIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_DROP_PROB.ordinal + it].controlId)
    }
    private val trackHumanGhostIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_GHOST_PROB.ordinal + it].controlId)
    }
    private val trackHumanOctaveIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_OCTAVE_PROB.ordinal + it].controlId)
    }
    private val trackHumanExtIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_EXT_PROB.ordinal + it].controlId)
    }
    private val trackFillEveryNIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_EVERY_N.ordinal + it].controlId)
    }
    private val trackFillTypeIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_TYPE.ordinal + it].controlId)
    }
    private val trackFillSkipProbIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_SKIP_PROB.ordinal + it].controlId)
    }
    private val trackChordFollowIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_CHORD_FOLLOW.ordinal + it].controlId)
    }
    private val trackLpgModeIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_MODE.ordinal + it].controlId)
    }
    private val trackLpgModeSpaceIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_MODE_SPACE.ordinal + it].controlId)
    }
    private val trackLpgDecayIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_DECAY.ordinal + it].controlId)
    }
    private val trackLpgColourIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_COLOUR.ordinal + it].controlId)
    }
    // Per-engine Space variants — pushed alongside the EDM-side ports above.
    // C++ render path picks between EDM and Space atomics based on which engine
    // slot is active for each track in the current render block.
    private val trackVolumeSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_VOLUME_SPACE.ordinal + it].controlId) }
    private val trackHarmonicsSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_SPACE.ordinal + it].controlId) }
    private val trackTimbreSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_TIMBRE_SPACE.ordinal + it].controlId) }
    private val trackMorphSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MORPH_SPACE.ordinal + it].controlId) }
    private val trackPinHarmonicsSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PIN_HARMONICS_SPACE.ordinal + it].controlId) }
    private val trackPinTimbreSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PIN_TIMBRE_SPACE.ordinal + it].controlId) }
    private val trackPinMorphSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PIN_MORPH_SPACE.ordinal + it].controlId) }
    private val trackHarmonicsModulationSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_MODULATION_SPACE.ordinal + it].controlId) }
    private val trackHarmonicsMacroSourceSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_MACRO_SOURCE_SPACE.ordinal + it].controlId) }
    private val trackHarmonicsMacroRangeSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_MACRO_RANGE_SPACE.ordinal + it].controlId) }
    private val trackModLfoRateSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_RATE_SPACE.ordinal + it].controlId) }
    private val trackModLfoDepthSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_DEPTH_SPACE.ordinal + it].controlId) }
    private val trackModLfoShapeSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_SHAPE_SPACE.ordinal + it].controlId) }
    private val trackModLfoCouplingSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_COUPLING_SPACE.ordinal + it].controlId) }
    private val trackHoldProbabilitySpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_PROBABILITY_SPACE.ordinal + it].controlId) }
    private val trackHoldLengthMinSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MIN_SPACE.ordinal + it].controlId) }
    private val trackHoldLengthMaxSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MAX_SPACE.ordinal + it].controlId) }
    private val trackDelaySendSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_SEND_SPACE.ordinal + it].controlId) }
    private val trackReverbSendSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_SEND_SPACE.ordinal + it].controlId) }
    private val trackNoteRangeLowSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_LOW_SPACE.ordinal + it].controlId) }
    private val trackNoteRangeHighSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_HIGH_SPACE.ordinal + it].controlId) }
    private val trackReverbBrightnessSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_BRIGHTNESS_SPACE.ordinal + it].controlId) }
    private val trackDelayFeedbackSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_FEEDBACK_SPACE.ordinal + it].controlId) }
    private val trackGlideRateSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_GLIDE_RATE_SPACE.ordinal + it].controlId) }
    private val trackLpgDecaySpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_DECAY_SPACE.ordinal + it].controlId) }
    private val trackLpgColourSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_COLOUR_SPACE.ordinal + it].controlId) }

    private val trackEvoTensionRespIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_TENSION_RESP.ordinal + it].controlId) }
    private val trackEvoNoteFollowIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_NOTE_FOLLOW.ordinal + it].controlId) }
    private val trackEvoPitchModeIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_PITCH_MODE.ordinal + it].controlId) }
    private val trackEvoVoicingTensionIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_VOICING_TENSION.ordinal + it].controlId) }

    private val muteSymbols = listOf(
        PulsarSymbol.TRACK_0_MUTE, PulsarSymbol.TRACK_1_MUTE,
        PulsarSymbol.TRACK_2_MUTE, PulsarSymbol.TRACK_3_MUTE,
        PulsarSymbol.TRACK_4_MUTE, PulsarSymbol.TRACK_5_MUTE,
        PulsarSymbol.TRACK_6_MUTE, PulsarSymbol.TRACK_7_MUTE,
    )

    private val selectedTrackFlow = MutableStateFlow<Int?>(null)
    private val _trackMutedFlow = MutableStateFlow(List(8) { false })
    // Initial vibe = the first provider in class-name order. Forces ONE vibe at construction;
    // the other 25 stay lazy until vibeList is iterated. Declared here — not in an init{}
    // further down — so it precedes _state's initializer below, which reads vibeFlow.value.
    //
    // PulsarFeature promises a non-null vibe, so the ViewModel holds it and mirrors every
    // write into PulsarSession, whose flow stays null until this first push (see setVibe).
    private val _vibeFlow = MutableStateFlow(curatedProviders.first().vibe)
    override val vibeFlow: StateFlow<Vibe> = _vibeFlow.asStateFlow()

    init {
        pulsarSession.updateVibe(_vibeFlow.value)
    }

    /** Single write path for the current vibe. Keeps the AppScope mirror in lockstep. */
    private fun setVibe(vibe: Vibe) {
        _vibeFlow.value = vibe
        pulsarSession.updateVibe(vibe)
    }
    private val restoreComplete = CompletableDeferred<Unit>()
    private val persistJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // Tracks the bpmMultiplier of the section we're currently in. Read/written
    // by the section-BPM collector and reset to 1.0f at every applyVibe (since
    // applyVibe resets globalTempo to vibe.bpm — the 1.0× baseline).
    @Volatile private var lastSectionMult: Float = 1.0f

    // Accelerando (Section.bpmRampBars): the in-flight tempo-ramp job, and a guard so a
    // ramp fires at most once per section visit. Cancelled + snapped-to-target on every
    // section change so the drop can never overshoot (the FireSky 0.5× clamp trap).
    @Volatile private var bpmRampJob: Job? = null
    private var rampStartedForSectionVisit: Boolean = false

    // Per-track effective send base values. Section overrides may swap these
    // between vibe load and the next vibe load — pushEffectiveSends reads from
    // here so per-section reverb/delay sends survive DEEP/SPACE knob updates.
    /**
     * Per-slot send bases. C++ resolves sends with PULSAR_PICK, so the Space slot needs its own
     * base or neither DEEP nor a section override reaches a track running its Space engine.
     *
     * Immutable and swapped as one reference: [applyVibe] runs on the caller's thread while the
     * arrangement collector runs on `dispatcherProvider.io`, and both rewrite every slot.
     */
    private class SendBases(
        val delayEdm: FloatArray = FloatArray(8),
        val reverbEdm: FloatArray = FloatArray(8),
        val delaySpace: FloatArray = FloatArray(8),
        val reverbSpace: FloatArray = FloatArray(8),
    )

    @Volatile private var sendBases = SendBases()

    // Seed BPM from GlobalTempo before controlIntents/stateFlow subscribe,
    // so the first emission matches the system tempo, not the plugin default.
    init {
        bpmId.value = FloatValue(globalTempo.getBpm().toFloat())
        // Restore saved state on startup, then re-apply after every preset load
        // (since applyPreset() resets all ports including Pulsar).
        scope.launch(dispatcherProvider.io) {
            restoreSavedState()
            restoreComplete.complete(Unit)
            presetLoader.presetFlow.collect {
                restoreSavedState()
            }
        }
        // Re-apply vibe after engine graph loads: the initial restore runs before
        // nativeLoadGraph which resets all engine atomics. Await graphReady to ensure
        // the graph is loaded before re-pushing the vibe.
        scope.launch(dispatcherProvider.io) {
            restoreComplete.await()
            synthEngine.graphReady.await()
            if (vibeFlow.value.arrangement != null) {
                log.debug { "Re-applying vibe after engine init" }
                applyVibe(vibeFlow.value)
                reapplyNotatedScore()
            }
        }
        // If the underlying C++ engine is recreated mid-session (Android: Oboe
        // rebuilds on audio route changes that shift sample rate), re-push the
        // current vibe recipe. DspSynthEngine handles graph + port-map sync;
        // the per-vibe track engines / harmonics / lick steps are NOT in the
        // port map, so without this re-push Pulsar would stay silent until
        // the user manually picks a vibe again. The notated score is in that
        // same non-port-mapped set, and re-applying the vibe alone would hand
        // its track back to the pattern generator.
        scope.launch(dispatcherProvider.io) {
            synthEngine.engineRecreatedFlow.collect {
                log.info { "Engine recreated — re-applying vibe ${vibeFlow.value.name}" }
                applyVibe(vibeFlow.value)
                reapplyNotatedScore()
            }
        }
    }

    /**
     * Re-push the score last applied, if any. Every path that re-applies the vibe must call
     * this: `applyVibe` pushes the vibe's own generative recipe, which includes the lick the
     * score-driven track would otherwise play. A no-op when no score is in play, so apps
     * with no [NotatedScoreProvider]s registered pay nothing.
     */
    private fun reapplyNotatedScore() {
        val id = notatedScoreIdFlow.value ?: return
        log.debug { "Re-applying notated score '$id'" }
        applyNotatedScore(id)
    }

    // Enrichment collector moved to PulsarSession (Step 1 of the PulsarSession extraction):
    // it was already self-sufficient given synthEngine and vibeFlow, both of which
    // PulsarSession now owns directly. Delegating here keeps this always-on for AppScope
    // consumers (media-session on Android Auto) exactly as before — PulsarSession's own
    // init{} subscribes regardless of whether this ViewModel has been constructed.
    override val arrangementStateFlow: StateFlow<PulsarArrangementState> get() = pulsarSession.arrangementStateFlow

    // ═══════════════════════════════════════════════════════════
    // Actions
    // ═══════════════════════════════════════════════════════════
    override val actions = PulsarPanelActions(
        setVibe = { vibe ->
            engagementTracker.record(EngagementAction.VIBE_SELECT)
            applyVibe(vibe)
        },
        setEnergy = energyId.floatSetter(),
        setComplexity = complexityId.floatSetter(),
        setSpace = { value ->
            spaceId.value = FloatValue(value)
            pushEffectiveSends(deepId.value.asFloat())
        },
        setMood = moodId.floatSetter(),
        setBpm = { bpm ->
            bpmId.value = FloatValue(bpm)
            globalTempo.setBpm(bpm.toDouble())
        },
        setDeep = { value ->
            deepId.value = FloatValue(value)
            pushEffectiveSends(value)
        },
        setRootNote = rootNoteId.intSetter(),
        setScale = scaleId.intSetter(),
        setMix = { value ->
            mixId.value = FloatValue(value)
        },
        setPercMix = percMixId.floatSetter(),
        setEnvelopeMode = envelopeModeId.intSetter(),
        selectTrack = {
            if (it != null) engagementTracker.record(EngagementAction.INSTRUMENTS_OPEN)
            selectedTrackFlow.value = it
        },
        setTrackEngineEdm = { track, engine ->
            trackEdmIds[track].value = IntValue(engine)
        },
        setTrackEngineSpace = { track, engine ->
            trackSpaceIds[track].value = IntValue(engine)
        },
        toggleTrackMute = { track ->
            val currentMuted = _trackMutedFlow.value[track]
            val newMuted = !currentMuted
            _trackMutedFlow.value = _trackMutedFlow.value.toMutableList().also { it[track] = newMuted }
            synthController.setPluginControl(muteSymbols[track].controlId, FloatValue(if (newMuted) 1f else 0f))
            log.debug { "Track $track ${if (newMuted) "muted" else "unmuted"}" }
        },
        songEndingEnabled = songEndingPreferences.enabledFlow,
        onSetSongEndingEnabled = { value ->
            scope.launch { songEndingPreferences.setEnabled(value) }
        },
        transitionSpec = transitionPreferences.defaultFlow,
        onSetTransitionStyle = { style ->
            engagementTracker.record(EngagementAction.TRANSITION_SELECT)
            val current = transitionPreferences.defaultFlow.value
            val clampedMs = current.handoffMs?.coerceIn(style.handoffRange)
                ?.takeIf { style.canHandoff }
            scope.launch { transitionPreferences.setDefault(current.copy(style = style, handoffMs = clampedMs)) }
        },
        onSetTransitionHandoffMs = { ms ->
            val current = transitionPreferences.defaultFlow.value
            val clamped = ms.coerceIn(current.style.handoffRange)
            scope.launch { transitionPreferences.setDefault(current.copy(handoffMs = clamped)) }
        },
        onSetTransitionRandomPool = { pool ->
            val current = transitionPreferences.defaultFlow.value
            scope.launch { transitionPreferences.setDefault(current.copy(randomPool = pool)) }
        },
        // Task 21 will replace this no-op with the bottom-sheet opener.
        onOpenTransitionSettings = { },
        activeTransition = transitionRunner.activeStyle,
        resolvedTransitionStyle = songEndingEventSource.resolvedTransitionStyle,
        finalSectionIndex = songEndingEventSource.finalSectionIndex,
        outroArmed = songEndingEventSource.endingTriggered,
        onArmOutro = { songEndingEventSource.armOutro() },
        onTriggerAnomaly = {
            val vibe = vibeFlow.value
            // Vibe.init rejects anomalies without an arrangement, but check again here too:
            // the engine only arms anomalies while an arrangement is active, and this guards
            // any future construction path that bypasses init.
            val hasAnomaly = vibe.anomalies.isNotEmpty() && vibe.arrangement != null
            // No declared (and armable) anomaly: skip the counter bump and highlight — nothing would fire.
            if (hasAnomaly) {
                anomalyRequestId.value = IntValue(anomalyRequestId.value.asInt() + 1)
                // Re-arming before the previous window elapses restarts the clock
                // rather than letting a rapid re-press clear early.
                anomalyArmedResetJob?.cancel()
                _anomalyArmed.value = true
                anomalyArmedResetJob = scope.launch {
                    delay(ANOMALY_HIGHLIGHT)
                    _anomalyArmed.value = false
                }
            }
        },
        anomalyArmed = _anomalyArmed.asStateFlow(),
    )

    // ═══════════════════════════════════════════════════════════
    // State flow
    // ═══════════════════════════════════════════════════════════
    private val controlIntents = merge(
        playingId.map { PulsarIntent.Playing(it.asInt() != 0) },
        mutedId.map { PulsarIntent.GlobalPaused(it.asInt() != 0) },
        vibeFlow.map { PulsarIntent.VibeChange(it) },
        notatedScoreIdFlow.map { PulsarIntent.NotatedScore(it) },
        energyId.map { PulsarIntent.Energy(it.asFloat()) },
        complexityId.map { PulsarIntent.Complexity(it.asFloat()) },
        spaceId.map { PulsarIntent.Space(it.asFloat()) },
        moodId.map { PulsarIntent.Mood(it.asFloat()) },
        bpmId.map { PulsarIntent.Bpm(it.asFloat()) },
        deepId.map { PulsarIntent.Deep(it.asFloat()) },
        rootNoteId.map { PulsarIntent.RootNote(it.asInt()) },
        scaleId.map { PulsarIntent.Scale(it.asInt()) },
        mixId.map { PulsarIntent.Mix(it.asFloat()) },
        percMixId.map { PulsarIntent.PercMix(it.asFloat()) },
        envelopeModeId.map { PulsarIntent.EnvelopeMode(it.asInt()) },
        selectedTrackFlow.map { PulsarIntent.SelectedTrack(it) },
        _trackMutedFlow.map { PulsarIntent.TrackMuteList(it) },
        *trackEdmIds.mapIndexed { i, flow ->
            flow.map { PulsarIntent.TrackEngineEdm(i, it.asInt()) }
        }.toTypedArray(),
        *trackSpaceIds.mapIndexed { i, flow ->
            flow.map { PulsarIntent.TrackEngineSpace(i, it.asInt()) }
        }.toTypedArray(),
    )

    private val _state = MutableStateFlow(PulsarUiState(
        bpm = globalTempo.getBpm().toFloat(),
        vibe = vibeFlow.value,
    ))
    override val stateFlow: StateFlow<PulsarUiState> = _state.asStateFlow()

    init {
        // Always-on reducer: drives state mutation independent of UI
        // subscription. MIDI / AI / preset intents reach the scan reducer even
        // when no panel is rendering (e.g. Android Auto background playback).
        // Idle when no intents emit — battery cost is the same as Eagerly here
        // because the upstream port flows are event-driven.
        scope.launch(dispatcherProvider.io) {
            controlIntents
                .scan(_state.value) { state, intent ->
                    when (intent) {
                        is PulsarIntent.Playing -> state.copy(playing = intent.value)
                        is PulsarIntent.GlobalPaused -> state.copy(globalPaused = intent.value)
                        is PulsarIntent.VibeChange -> state.copy(vibe = intent.value, vibeName = intent.value.name)
                        is PulsarIntent.NotatedScore -> state.copy(notatedScoreId = intent.value)
                        is PulsarIntent.Energy -> state.copy(energy = intent.value)
                        is PulsarIntent.Complexity -> state.copy(complexity = intent.value)
                        is PulsarIntent.Space -> state.copy(space = intent.value)
                        is PulsarIntent.Mood -> state.copy(mood = intent.value)
                        is PulsarIntent.Bpm -> state.copy(bpm = intent.value)
                        is PulsarIntent.Deep -> state.copy(deep = intent.value)
                        is PulsarIntent.RootNote -> state.copy(rootNote = intent.value)
                        is PulsarIntent.Scale -> state.copy(scaleIndex = intent.value)
                        is PulsarIntent.Mix -> state.copy(mix = intent.value)
                        is PulsarIntent.PercMix -> state.copy(percMix = intent.value)
                        is PulsarIntent.EnvelopeMode -> state.copy(envelopeMode = intent.value)
                        is PulsarIntent.SelectedTrack -> state.copy(selectedTrack = intent.value)
                        is PulsarIntent.TrackEngineEdm -> state.copy(
                            trackEnginesEdm = state.trackEnginesEdm.toMutableList().also {
                                it[intent.track] = intent.engine
                            }
                        )
                        is PulsarIntent.TrackEngineSpace -> state.copy(
                            trackEnginesSpace = state.trackEnginesSpace.toMutableList().also {
                                it[intent.track] = intent.engine
                            }
                        )
                        is PulsarIntent.TrackMuteList -> state.copy(trackMuted = intent.muteList)
                    }
                }
                .flowOn(dispatcherProvider.io)
                .collect { _state.value = it }
        }
    }

    init {
        scope.launch(dispatcherProvider.io) {
            globalTempo.bpm.collect { bpm ->
                bpmId.value = FloatValue(bpm.toFloat())
            }
        }
        // Per-section BPM + accelerando. Two concerns keyed off the arrangement flow, in ONE
        // collector (sequential event processing = no racing BPM writes):
        //   (1) Section change — scale the live BPM by (newMult / oldMult). Composes with
        //       user-dialed tempo edits (a half-time breakdown stays half-time even if the
        //       user dialed BPM in the previous section). lastSectionMult resets to 1.0 on
        //       each vibe load (see applyVibe), so the first section's mult applies on top
        //       of vibe.bpm.
        //   (2) Section.bpmRampBars accelerando — over the section's tail, wind the BPM up
        //       from its half-time tempo to full base tempo, landing on the next downbeat.
        //       Runs as a child job; the section-change branch cancels it and snaps to the
        //       target, so an interrupted or completed wind-up never overshoots the drop.
        scope.launch(dispatcherProvider.io) {
            var lastSectionIndex = -1
            arrangementStateFlow
                .filter { it.sectionIndex >= 0 }
                .collect { arr ->
                    val vibe = vibeFlow.value
                    val sections = vibe.arrangement?.sections
                    val sectionIndex = arr.sectionIndex

                    // (1) Section change: settle the tempo definitively.
                    if (sectionIndex != lastSectionIndex) {
                        lastSectionIndex = sectionIndex
                        val wasRamping = rampStartedForSectionVisit
                        rampStartedForSectionVisit = false
                        // Fully STOP any in-flight accelerando before touching tempo.
                        // cancelAndJoin (not cancel) matters: cancel is cooperative, so a bare
                        // cancel lets the ramp fire one more low setBpm AFTER ours, leaving the
                        // drop stuck mid-wind-up. Joining guarantees the ramp is dead first.
                        bpmRampJob?.cancelAndJoin()
                        bpmRampJob = null
                        val newMult = sections?.getOrNull(sectionIndex)?.bpmMultiplier ?: 1.0f
                        val oldMult = lastSectionMult
                        lastSectionMult = newMult
                        if (wasRamping) {
                            // Coming out of an accelerando (which left the live BPM mid-flight):
                            // land the new section at its absolute tempo so the drop is exact.
                            globalTempo.setBpm((vibe.bpm * newMult).toDouble())
                        } else if (newMult != oldMult) {
                            // Normal per-section flip: scale live BPM by the mult ratio so it
                            // composes with any user-dialed tempo edits.
                            val currentBpm = globalTempo.getBpm().toFloat()
                            val effective = currentBpm * (newMult / oldMult)
                            if (kotlin.math.abs(effective - currentBpm) > 0.01f) {
                                globalTempo.setBpm(effective.toDouble())
                            }
                        }
                        log.debug { "SectionTempo: sec=$sectionIndex settled @ ${globalTempo.getBpm().toInt()} BPM" }
                    }

                    // (2) Accelerando: fire once when we enter the section's last window.
                    val section = sections?.getOrNull(sectionIndex) ?: return@collect
                    val rampBars = section.bpmRampBars
                    if (rampBars > 0 && !rampStartedForSectionVisit && arr.barsTotal > 0 &&
                        arr.barsElapsed >= arr.barsTotal - rampBars
                    ) {
                        rampStartedForSectionVisit = true
                        val startBpm = vibe.bpm * section.bpmMultiplier
                        val endBpm = vibe.bpm  // full base tempo = the drop target
                        // Pre-set lastSectionMult to the target so a section flip mid-ramp
                        // composes correctly (no overshoot) even before the ramp finishes.
                        lastSectionMult = 1.0f
                        if (endBpm - startBpm > 0.01f) {
                            log.debug { "Accelerando: sec=$sectionIndex ${startBpm.toInt()}->${endBpm.toInt()} BPM over $rampBars bar(s)" }
                            bpmRampJob = launch(dispatcherProvider.io) {
                                rampBpm(startBpm, endBpm, rampBars)
                            }
                        }
                    }
                }
        }
        // Per-section per-track overrides: on each section change, apply the
        // section's TrackSectionOverride to each track (or restore the vibe's
        // base value when no override is present). Static atomics flip
        // immediately; sends update via pushEffectiveSends.
        // Deliberately NOT keyed on a vibe-load generation. arrangementStateFlow is a 5Hz poll
        // of the engine and nothing resets it on load, so at the moment of a vibe switch it
        // still holds the OUTGOING vibe's sectionIndex — firing on the load would apply the
        // wrong section's overrides and stomp applyVibe's synchronous push for ~200ms.
        // applyVibe covers the load; this collector covers section changes.
        scope.launch(dispatcherProvider.io) {
            arrangementStateFlow
                .map { it.sectionIndex }
                .filter { it >= 0 }
                .distinctUntilChanged()
                .collect { sectionIndex ->
                    applyTrackOverridesForSection(sectionIndex)
                }
        }
        // NOTE: a per-edge ScratchEffect (SectionTransition.effects) is triggered in C++,
        // not here. The arrangement flow is a 5Hz poll (up to 200ms latency), far too
        // coarse to fire a scratch AT the boundary and hold the drop's first steps. The
        // pulsar unit arms the master scratch synchronously at the section flip and freezes
        // its own clock while the scratch is active, so the incoming section can't advance
        // until the scratch drops. See orpheus_unit_pulsar.cpp (section-changed handler).
        // Debounced save: waits for restore to complete first to avoid saving stale defaults.
        // Timeout ensures saving isn't blocked forever if presetFlow never emits.
        scope.launch(dispatcherProvider.io) {
            withTimeoutOrNull(5_000L) { restoreComplete.await() }
            stateFlow.drop(1).debounce(2_000L).collect { state ->
                saveState(state)
            }
        }
    }

    /**
     * Accelerando helper: ease the live BPM from [startBpm] up to [endBpm] over a wall-clock
     * window sized to [rampBars] arrangement-bars (at the average of the two tempi, clamped to
     * a musical 1.5–5 s), with an ease-in (t²) so the wind-up accelerates into the drop.
     * Cancellable mid-ramp via [bpmRampJob]; a section change snaps to the target, so this
     * need not run to completion. BPM flows out via globalTempo → bpmId → the C++ clock.
     */
    private suspend fun rampBpm(startBpm: Float, endBpm: Float, rampBars: Int) {
        // HOLD at the slow tempo through most of the window, then a QUICK wind-up over ~the last
        // couple beats into the drop. A whole-bar ramp drags; the punch belongs at the very end.
        val beatsPerBar = 8f  // one arrangement-bar (track-0 loop) ≈ 2 real bars = 8 beats
        val windowMs = (rampBars * beatsPerBar * (60_000f / startBpm)).toLong()
        val windUpMs = 1_300L  // the quick accelerando itself (~2 beats)
        val holdMs = (windowMs - windUpMs).coerceAtLeast(0L)
        if (holdMs > 0) delay(holdMs)
        val stepMs = 40L
        val steps = (windUpMs / stepMs).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            globalTempo.setBpm((startBpm + (endBpm - startBpm) * (t * t)).toDouble())
            delay(stepMs)
        }
        globalTempo.setBpm(endBpm.toDouble())
    }

    // ═══════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════
    private suspend fun restoreSavedState() {
        val json = appPreferencesRepository.load().lastPulsarJson ?: return
        val saved = try {
            persistJson.decodeFromString<PulsarUiState>(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to restore Pulsar state" }
            return
        }
        // Push saved values into control flows (drives both UI and C++ engine)
        // Mix is intentionally excluded — always starts at 0 (off)
        // Always resolve vibe from current definitions so structural updates
        // (arrangement, tracks) take effect. Try vibeName first (new format),
        // fall back to saved vibe name (old format), then first vibe.
        val name = saved.vibeName.ifEmpty { saved.vibe.name }
        // Use provider.name (cheap) to look up by name without forcing all
        // 26 vibes to materialize. Only the matched provider's `vibe` is built.
        val provider = curatedProviders.firstOrNull { it.name == name }
            ?: curatedProviders.first()
        applyVibe(provider.vibe)
        // Then override with saved macro values
        energyId.value = FloatValue(saved.energy)
        complexityId.value = FloatValue(saved.complexity)
        spaceId.value = FloatValue(saved.space)
        moodId.value = FloatValue(saved.mood)
        // saved.bpm is the user's BASE (1.0x) tempo — scale it by the OPENING section's
        // multiplier, exactly as applyVibe(above) does, so a half-time intro is restored at
        // half-time and not the flat body tempo. BOTH pushes must be multiplied: bpmId feeds
        // the pulsar clock's live override, and globalTempo must match or the globalTempo->bpmId
        // collector would overwrite bpmId back to the flat value. applyVibe already seeded
        // lastSectionMult to introMult, so the section-0 collector stays idempotent. (In
        // MIX_GATED, presetFlow re-runs this after the graph-ready re-apply, so this push — not
        // applyVibe's — is the last writer; a flat value here is the "intro at default" bug.)
        val savedArrangement = provider.vibe.arrangement
        val introMult = savedArrangement?.introIndex
            ?.let { savedArrangement.sections.getOrNull(it)?.bpmMultiplier } ?: 1.0f
        bpmId.value = FloatValue(saved.bpm * introMult)
        globalTempo.setBpm((saved.bpm * introMult).toDouble())
        deepId.value = FloatValue(saved.deep)
        pushEffectiveSends(saved.deep)
        percMixId.value = FloatValue(saved.percMix)
        envelopeModeId.value = IntValue(saved.envelopeMode)
        // The written part is not in the port map and applyVibe above just pushed the
        // generative lick over its track, so the score has to be restored with the vibe --
        // otherwise a saved classical selection comes back playing the wrong notes.
        saved.notatedScoreId?.let { applyNotatedScore(it) }
        log.debug { "Restored Pulsar state: vibe=${saved.vibe.name}, bpm=${saved.bpm}" }
    }

    private suspend fun saveState(state: PulsarUiState) {
        // Strip transient fields; mutes are never persisted.
        // MIX_GATED (Orpheus): mix saves as 0 (user dials it in each session).
        // EXPLICIT (DJ app): mix saves as 1 (always audible, play/pause gates audio).
        val savedMix = if (playbackMode == PulsarPlaybackMode.MIX_GATED) 0f else 1f
        val toSave = state.copy(playing = false, selectedTrack = null, mix = savedMix, trackMuted = List(8) { false })
        val json = persistJson.encodeToString(PulsarUiState.serializer(), toSave)
        appPreferencesRepository.update { it.copy(lastPulsarJson = json) }
    }

    // ═══════════════════════════════════════════════════════════
    // Vibe application
    // ═══════════════════════════════════════════════════════════

    private fun compingStyleToInt(style: CompingStyle?): Int =
        style?.engineId ?: 0  // PAD (default for non-CHORDAL tracks — harmless)

    override fun applyVibeByName(name: String): Boolean {
        val provider = curatedProviders.firstOrNull { it.name == name } ?: return false
        applyVibe(provider.vibe)
        return true
    }

    /**
     * Push the entire vibe recipe to C++. Called from setVibe action and restoreSavedState.
     */
    override fun applyVibe(vibe: Vibe) {
        log.info { "applyVibe name=${vibe.name} bpm=${vibe.bpm} tracks=${vibe.tracks.size} sections=${vibe.arrangement?.sections?.size ?: 0}" }
        // Set vibeFlow first so pushEffectiveSends reads the new vibe's per-track sends.
        setVibe(vibe)
        // A new song starts here. Must be explicit, not a vibeFlow observation:
        // a re-apply of the playing vibe emits nothing, stranding the outro.
        // Before pushArrangement so the cleared port precedes its fence.
        songEndingEventSource.onVibeApplied()
        // globalTempo will be set to vibe.bpm below — that is the 1.0× baseline
        // for the section-BPM collector to compose multipliers against.
        lastSectionMult = 1.0f

        // A new vibe load clears any stale Void Anomaly tint immediately
        // rather than waiting out ANOMALY_HIGHLIGHT.
        anomalyArmedResetJob?.cancel()
        _anomalyArmed.value = false

        // Reset all track mutes on vibe load
        _trackMutedFlow.value = List(8) { false }
        for (i in 0 until 8) {
            synthController.setPluginControl(muteSymbols[i].controlId, FloatValue(0f))
        }

        // Push per-track voice params.
        // Engine character knobs (volume, harmonics, timbre, morph, mod LFO, holds,
        // sends, range, reverbBrightness, delayFeedback, glide, LPG) live on
        // OrpheusEngine. Each per-engine knob has _edm + _space ports — the
        // C++ render path picks between them per render block based on which
        // slot is active (engine_index == active_edm vs Space).
        vibe.tracks.forEachIndexed { i, tv ->
            val edm = tv.engineEdm
            val spa = tv.engineSpace
            trackEdmIds[i].value = IntValue(edm.engineId.id)
            trackSpaceIds[i].value = IntValue(spa.engineId.id)
            trackVolumeIds[i].value = FloatValue(edm.volume)
            trackVolumeSpaceIds[i].value = FloatValue(spa.volume)
            trackPanIds[i].value = FloatValue(tv.pan)
            trackHarmonicsIds[i].value = FloatValue(edm.harmonics)
            trackHarmonicsSpaceIds[i].value = FloatValue(spa.harmonics)
            trackTimbreIds[i].value = FloatValue(edm.timbre)
            trackTimbreSpaceIds[i].value = FloatValue(spa.timbre)
            trackMorphIds[i].value = FloatValue(edm.morph)
            trackMorphSpaceIds[i].value = FloatValue(spa.morph)
            // Effective pin: vibe-author opt-in OR engine-enforced (DX-family
            // harmonics is a quantized 32-step patch selector — never let the
            // macro range slide between patches).
            val pinEdmHarmonics = edm.pinHarmonics || edm.engineId.forcePinHarmonics
            val pinSpaHarmonics = spa.pinHarmonics || spa.engineId.forcePinHarmonics
            trackPinHarmonicsIds[i].value = IntValue(if (pinEdmHarmonics) 1 else 0)
            trackPinHarmonicsSpaceIds[i].value = IntValue(if (pinSpaHarmonics) 1 else 0)
            trackPinTimbreIds[i].value = IntValue(if (edm.pinTimbre) 1 else 0)
            trackPinTimbreSpaceIds[i].value = IntValue(if (spa.pinTimbre) 1 else 0)
            trackPinMorphIds[i].value = IntValue(if (edm.pinMorph) 1 else 0)
            trackPinMorphSpaceIds[i].value = IntValue(if (spa.pinMorph) 1 else 0)
            trackHarmonicsModulationIds[i].value = FloatValue(edm.harmonicsModulation)
            trackHarmonicsModulationSpaceIds[i].value = FloatValue(spa.harmonicsModulation)
            trackHarmonicsMacroSourceIds[i].value = IntValue(edm.harmonicsMacroSource.ordinal)
            trackHarmonicsMacroSourceSpaceIds[i].value = IntValue(spa.harmonicsMacroSource.ordinal)
            trackHarmonicsMacroRangeIds[i].value = FloatValue(edm.harmonicsMacroRange)
            trackHarmonicsMacroRangeSpaceIds[i].value = FloatValue(spa.harmonicsMacroRange)
            trackEnvelopeIds[i].value = IntValue(tv.envelopeProfile.id)
            trackRoleIds[i].value = IntValue(tv.role.engineId)
            trackBarStrategyIds[i].value = IntValue(tv.barStrategy.id)
            trackEvoRhythmicIds[i].value = IntValue(if (tv.evolution.rhythmic != null) 1 else 0)
            trackEvoTensionRespIds[i].value = FloatValue(tv.evolution.rhythmic?.tensionResponse ?: 1.0f)
            trackEvoNoteFollowIds[i].value = IntValue(tv.evolution.rhythmic?.noteFollow?.ordinal ?: 0)
            trackEvoPitchModeIds[i].value = IntValue(when (tv.evolution.pitch) {
                null -> 0
                is PitchEvolution.Contour -> 1
                is PitchEvolution.Voicing -> 2
            })
            trackEvoVoicingTensionIds[i].value = FloatValue(
                (tv.evolution.pitch as? PitchEvolution.Voicing)?.tensionResponse ?: 1.0f
            )
            pushMacroMap(i, tv.macroMap)
            trackModLfoRateIds[i].value = FloatValue(edm.modLfoRate)
            trackModLfoRateSpaceIds[i].value = FloatValue(spa.modLfoRate)
            trackModLfoDepthIds[i].value = FloatValue(edm.modLfoDepth)
            trackModLfoDepthSpaceIds[i].value = FloatValue(spa.modLfoDepth)
            trackModLfoShapeIds[i].value = FloatValue(edm.modLfoShape)
            trackModLfoShapeSpaceIds[i].value = FloatValue(spa.modLfoShape)
            trackModLfoCouplingIds[i].value = FloatValue(edm.modLfoCoupling)
            trackModLfoCouplingSpaceIds[i].value = FloatValue(spa.modLfoCoupling)
            trackHoldProbabilityIds[i].value = FloatValue(edm.holdProbability)
            trackHoldProbabilitySpaceIds[i].value = FloatValue(spa.holdProbability)
            trackHoldLengthMinIds[i].value = IntValue(edm.holdLengthMin)
            trackHoldLengthMinSpaceIds[i].value = IntValue(spa.holdLengthMin)
            trackHoldLengthMaxIds[i].value = IntValue(edm.holdLengthMax)
            trackHoldLengthMaxSpaceIds[i].value = IntValue(spa.holdLengthMax)
            trackNoteRangeLowIds[i].value = IntValue(edm.noteRangeLow)
            trackNoteRangeLowSpaceIds[i].value = IntValue(spa.noteRangeLow)
            trackNoteRangeHighIds[i].value = IntValue(edm.noteRangeHigh)
            trackNoteRangeHighSpaceIds[i].value = IntValue(spa.noteRangeHigh)
            trackReverbBrightnessIds[i].value = FloatValue(edm.reverbBrightness)
            trackReverbBrightnessSpaceIds[i].value = FloatValue(spa.reverbBrightness)
            genreDensityIds[i].value = FloatValue(tv.density)
            trackDelayFeedbackIds[i].value = FloatValue(edm.delayFeedback ?: -1f)
            trackDelayFeedbackSpaceIds[i].value = FloatValue(spa.delayFeedback ?: -1f)
            trackGlideRateIds[i].value = FloatValue(edm.glideRate)
            trackGlideRateSpaceIds[i].value = FloatValue(spa.glideRate)
            trackLickModeIds[i].value = IntValue(when (tv.lickMode) {
                is LickMode.None -> 0
                is LickMode.Squash -> 1
                is LickMode.Fill -> 2
            })
            trackLickDegreeOffsetIds[i].value = IntValue(tv.lickDegreeOffset)
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "track_lick_source_$i"),
                FloatValue(
                    if ((tv.role as? TrackRole.Melodic)?.lickSource == LickSource.BASS) 1f else 0f
                ))
            trackCompingStyleIds[i].value = IntValue(compingStyleToInt(tv.chordComping?.style))
            trackArpModeIds[i].value = IntValue(tv.chordComping?.arpMode?.ordinal ?: 0)
            trackArpSpeedIds[i].value = FloatValue(tv.chordComping?.arpSpeed ?: 0.2f)
            trackArpDirectionIds[i].value = IntValue(tv.chordComping?.arpDirection?.ordinal ?: 0)
            trackInversionIds[i].value = IntValue(tv.chordComping?.sectionInversion?.ordinal ?: 0)
            trackHumanDropIds[i].value = FloatValue(tv.chordComping?.humanization?.dropProbability ?: 0f)
            trackHumanGhostIds[i].value = FloatValue(tv.chordComping?.humanization?.ghostProbability ?: 0f)
            trackHumanOctaveIds[i].value = FloatValue(tv.chordComping?.humanization?.octaveJumpProbability ?: 0f)
            trackHumanExtIds[i].value = FloatValue(tv.chordComping?.humanization?.extensionProbability ?: 0f)
            trackFillEveryNIds[i].value = IntValue(tv.chordComping?.fills?.everyNBars ?: 0)
            trackFillTypeIds[i].value = IntValue(tv.chordComping?.fills?.fillType?.ordinal ?: 0)
            trackFillSkipProbIds[i].value = FloatValue(tv.chordComping?.fills?.skipProbability ?: 0f)
            trackChordFollowIds[i].value = IntValue(tv.chordFollow.ordinal)
            // LPG mode is per-engine: edm.lpgMode applies to the EDM slot,
            // engineSpace.lpgMode to the SPACE slot. C++ selects per render block
            // based on which engine is active (orpheus_unit_pulsar.cpp:2451).
            trackLpgModeIds[i].value = IntValue(edm.lpgMode.id)
            trackLpgModeSpaceIds[i].value = IntValue(spa.lpgMode.id)
            trackLpgDecayIds[i].value = FloatValue(edm.lpgDecay)
            trackLpgDecaySpaceIds[i].value = FloatValue(spa.lpgDecay)
            trackLpgColourIds[i].value = FloatValue(edm.lpgColour)
            trackLpgColourSpaceIds[i].value = FloatValue(spa.lpgColour)
        }
        // Seed per-slot send bases from the vibe; section overrides may swap these later.
        // pushEffectiveSends scales both slots by DEEP and publishes the ports.
        sendBases = SendBases().apply {
            for (i in 0 until 8) {
                val tv = vibe.tracks.getOrNull(i)
                delayEdm[i] = tv?.engineEdm?.delaySend ?: 0f
                reverbEdm[i] = tv?.engineEdm?.reverbSend ?: 0f
                delaySpace[i] = tv?.engineSpace?.delaySend ?: 0f
                reverbSpace[i] = tv?.engineSpace?.reverbSend ?: 0f
            }
        }
        stepCountId.value = IntValue(vibe.stepCount)
        pushEffectiveSends(deepId.value.asFloat())

        // Push vibe-defined effect params to dedicated Pulsar delay/reverb
        val fx = vibe.effects
        pulsarDelayTimeAId.value = FloatValue(fx.delayTimeA)
        pulsarDelayTimeBId.value = FloatValue(fx.delayTimeB)
        pulsarDelayFeedbackId.value = FloatValue(fx.delayFeedback)
        pulsarDelayDampingId.value = FloatValue(fx.delayDamping)
        pulsarReverbSizeId.value = FloatValue(fx.reverbSize)
        pulsarReverbDampingId.value = FloatValue(fx.reverbDamping)
        pulsarReverbBrightnessId.value = FloatValue(fx.reverbBrightness)

        // Push genre profile
        genreSwingId.value = FloatValue(vibe.genre.swingAmount)
        genreGhostProbId.value = FloatValue(vibe.genre.ghostProbability)
        genreNoteRangeLowId.value = IntValue(vibe.genre.noteRangeLow)
        genreNoteRangeHighId.value = IntValue(vibe.genre.noteRangeHigh)
        genreRhythmDensityId.value = FloatValue(vibe.genre.rhythmDensity)
        genreProgressionStyleId.value = IntValue(vibe.genre.progressionStyle.ordinal)
        genreChordsPerBarId.value = IntValue(vibe.genre.chordsPerBar)
        progressionAnchorId.value = IntValue(vibe.progressionAnchor.barsBetweenResets)
        progressionDriftRangeId.value = FloatValue(vibe.progressionDriftRange)

        // Custom chord transition matrix (49 floats = 7x7 row-major)
        val chordMatrix = vibe.genre.chordTransitionMatrix
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "chord_matrix_active"),
            IntValue(if (chordMatrix != null) 1 else 0)
        )
        if (chordMatrix != null) {
            for (i in 0 until minOf(chordMatrix.size, 49)) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "chord_matrix_$i"),
                    FloatValue(chordMatrix[i])
                )
            }
        }

        // Custom progression (optional chord sequence override, max 12 slots —
        // kMaxProgressionLength, e.g. a literal 12-bar blues).
        // Write degrees + glides first, then length (acts as a release fence on
        // the C++ side). Per-chord glide is applied on transition into each chord.
        val customProg = vibe.genre.customProgression
        if (customProg != null) {
            customProg.forEachIndexed { i, step ->
                if (i < 12) {
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "custom_progression_$i"),
                        IntValue(step.degree)
                    )
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "custom_progression_glide_$i"),
                        FloatValue(step.glideRate)
                    )
                }
            }
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "custom_progression_length"),
                IntValue(minOf(customProg.size, 12))
            )
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "custom_progression_active"),
                IntValue(1)
            )
        } else {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "custom_progression_active"),
                IntValue(0)
            )
        }

        // Push lick (data first, length last as release fence)
        val lick = vibe.lick
        if (lick != null) {
            lick.steps.forEachIndexed { i, step ->
                val base = i * Lick.LICK_FIELDS_PER_STEP
                lickDataIds[base].value = FloatValue(step.scaleDegree.toFloat())
                lickDataIds[base + 1].value = FloatValue(step.duration)
                lickDataIds[base + 2].value = FloatValue(step.velocity)
                // -1 = "use the track's TrackVoice.glideRate"; explicit value overrides.
                lickDataIds[base + 3].value = FloatValue(step.glideRate)
            }
            lickMutationId.value = FloatValue(vibe.lickMutation)
            lickOctaveId.value = IntValue(vibe.lickOctave)
            lickLoopLengthId.value = IntValue(lick.loopLength)
            lickLengthId.value = IntValue(lick.steps.size)
        } else {
            lickLoopLengthId.value = IntValue(0)
            lickLengthId.value = IntValue(0)
        }

        // Push bass line channel (Pattern B dynamic controls; data first,
        // bass_line_length last as release fence, mirroring the lead-lick contract).
        val bassLine = vibe.bassLine
        if (bassLine != null) {
            bassLine.steps.forEachIndexed { i, step ->
                val base = i * Lick.LICK_FIELDS_PER_STEP
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "bass_line_data_$base"),
                    FloatValue(step.scaleDegree.toFloat()))
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "bass_line_data_${base + 1}"),
                    FloatValue(step.duration))
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "bass_line_data_${base + 2}"),
                    FloatValue(step.velocity))
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "bass_line_data_${base + 3}"),
                    FloatValue(step.glideRate))
            }
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "bass_line_mutation"),
                FloatValue(vibe.bassLineMutation))
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "bass_line_octave"),
                FloatValue(vibe.bassLineOctave.toFloat()))
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "bass_line_loop"),
                FloatValue(bassLine.loopLength.toFloat()))
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "bass_line_length"),
                FloatValue(bassLine.steps.size.toFloat()))
        } else {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "bass_line_length"), FloatValue(0f))
        }

        // Push lick rotation pool (Pattern B dynamic controls: bank data first,
        // pool_count last as the release fence). Bank layout: rotation members in
        // slots 0..poolPart.size-1, then the LickAnomaly lick (if any) in the next slot.
        //
        // Rotation lives in vibe.lickRotation.pool; the anomaly lives in vibe.anomalies as a
        // LickAnomaly. They recombine here into the SAME C++ lick bank the old embedded-anomaly
        // schema produced, so the DSP wire format is unchanged. A LickAnomaly with only a single
        // vibe.lick (no rotation) becomes a rotation-of-1 with the anomaly in slot 1.
        val la = vibe.anomalies.filterIsInstance<LickAnomaly>().firstOrNull()
        val poolPart: List<Lick> = vibe.lickRotation?.pool ?: listOfNotNull(vibe.lick)
        if ((la != null || vibe.lickRotation != null) && poolPart.isNotEmpty()) {
            val bank = poolPart + listOfNotNull(la?.lick)
            val anomalyIndex = if (la != null) poolPart.size else -1
            bank.forEachIndexed { slot, l ->
                l.steps.forEachIndexed { step, s ->
                    val base = slot * (Lick.MAX_LICK_STEPS * Lick.LICK_FIELDS_PER_STEP) +
                        step * Lick.LICK_FIELDS_PER_STEP
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "lick_pool_data_$base"),
                        FloatValue(s.scaleDegree.toFloat()))
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "lick_pool_data_${base + 1}"),
                        FloatValue(s.duration))
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "lick_pool_data_${base + 2}"),
                        FloatValue(s.velocity))
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "lick_pool_data_${base + 3}"),
                        FloatValue(s.glideRate))
                }
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "lick_pool_len_$slot"),
                    FloatValue(l.steps.size.toFloat()))
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "lick_pool_loop_$slot"),
                    FloatValue(l.loopLength.toFloat()))
            }
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "lick_anomaly_index"), FloatValue(anomalyIndex.toFloat()))
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "lick_anomaly_chance"), FloatValue(la?.chance ?: 0f))
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "lick_pool_count"), FloatValue(poolPart.size.toFloat()))
        } else {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "lick_pool_count"), FloatValue(0f))
        }

        seedId.value = IntValue(vibe.seed)
        envelopeModeId.value = IntValue(vibe.envelopeType.modeIndex)

        // Push tension profile
        val t = vibe.tension
        tensionInnerBarsId.value = IntValue(t.innerBars)
        tensionOuterBarsId.value = IntValue(t.outerBars)
        tensionOuterDepthId.value = FloatValue(t.outerDepth)
        tensionVolumeId.value = FloatValue(t.volume)
        tensionTimingId.value = FloatValue(t.timing)
        tensionOctaveShiftId.value = IntValue(if (t.tonal.octaveShift) 1 else 0)
        tensionKeyShiftId.value = IntValue(t.tonal.keyShift)
        tensionHalfLickId.value = IntValue(t.tonal.halfLick.ordinal)
        tensionChromaticPassingId.value = FloatValue(t.tonal.chromaticPassing)
        val e = t.evolution
        tensionEvoTimbreLowId.value = FloatValue(e.timbreLow)
        tensionEvoTimbreHighId.value = FloatValue(e.timbreHigh)
        tensionEvoTimbreProbId.value = FloatValue(e.timbreProbability)
        tensionEvoMorphLowId.value = FloatValue(e.morphLow)
        tensionEvoMorphHighId.value = FloatValue(e.morphHigh)
        tensionEvoMorphProbId.value = FloatValue(e.morphProbability)
        tensionEvoHarmLowId.value = FloatValue(e.harmonicsLow)
        tensionEvoHarmHighId.value = FloatValue(e.harmonicsHigh)
        tensionEvoHarmProbId.value = FloatValue(e.harmonicsProbability)
        tensionEvoAttackPointId.value = FloatValue(e.attackPoint)
        tensionEvoReleaseSpeedId.value = FloatValue(e.releaseSpeed)
        tensionSpurtChanceId.value = FloatValue(t.spurtChance)
        // Per-track evolution weight
        vibe.tracks.forEachIndexed { i, tv ->
            trackEvoWeightIds[i].value = FloatValue(tv.evolutionWeight)
        }

        // Set macro defaults
        rootNoteId.value = IntValue(vibe.rootNote.noteIndex)
        scaleId.value = IntValue(vibe.scaleType.scaleIndex)
        // Apply the OPENING section's bpmMultiplier here, synchronously, so the intro
        // tempo is deterministic on a vibe switch. bpmId feeds the C++ pulsar clock's
        // pulsar_bpm_override, which the audio thread reads live and in PRIORITY over the
        // global clock_bpm — so bpmId must carry the ALREADY-MULTIPLIED live tempo. Pushing
        // the flat vibe.bpm here leaves a half-time opening section (e.g. FireSky's ~60 BPM
        // intro) running at the full body tempo until a later section change. The section-BPM
        // collector is gated by distinctUntilChanged on sectionIndex; when the previous vibe
        // was already on section 0, the new vibe's section-0 re-emission is suppressed and the
        // collector never applies the multiplier. Seeding lastSectionMult keeps the collector
        // idempotent: when section 0 does emit, newMult == oldMult and it early-returns.
        //
        // null = C++ picks a random weighted start (pushArrangement sends -1), so no opening
        // section is knowable here and everything below falls back to the flat vibe defaults.
        val arrangement = vibe.arrangement
        val introIdx = arrangement?.introIndex
        val introMult = introIdx
            ?.let { arrangement.sections.getOrNull(it)?.bpmMultiplier } ?: 1.0f
        bpmId.value = FloatValue(vibe.bpm * introMult)
        globalTempo.setBpm((vibe.bpm * introMult).toDouble())
        lastSectionMult = introMult
        energyId.value = FloatValue(vibe.energy)
        complexityId.value = FloatValue(vibe.complexity)
        spaceId.value = FloatValue(vibe.space)
        moodId.value = FloatValue(vibe.mood)
        deepId.value = FloatValue(vibe.deep)

        // Push arrangement data (MUST be before vibe generation increment)
        pushArrangement(vibe)

        // Opening section's per-track overrides, synchronously and BEFORE the generation bump:
        // load_vibe snapshots pulsar_track_envelope into track_solo_behavior and never refreshes
        // it, so writing after the bump races that snapshot. The flow collector cannot cover this
        // — it is a 5Hz poll, and audio is already live in EXPLICIT mode.
        // applyPatternInputs = false is required; see its KDoc.
        introIdx?.let { applyTrackOverridesForSection(it, applyPatternInputs = false) }

        // In MIX_GATED mode (Orpheus), auto-start on vibe load — mix knob controls audibility.
        // In EXPLICIT mode (DJ app), playing is controlled by PulsarPlaybackBridge, mix stays at 1.
        if (playbackMode == PulsarPlaybackMode.MIX_GATED) {
            playingId.value = IntValue(1)
            mixId.value = FloatValue(0f)
        } else {
            mixId.value = FloatValue(1f)
        }

        // Trigger vibe reload (MUST be last — load_vibe consumes every port pushed above)
        vibeGenerationId.value = IntValue(vibeGenerationId.value.asInt() + 1)
    }

    /**
     * Push the 16 macro map floats for a single track.
     */
    private fun pushMacroMap(trackIndex: Int, map: TrackMacroMap) {
        val ids = trackMacroIds[trackIndex]
        ids[0].value = FloatValue(map.energyVolume.min)
        ids[1].value = FloatValue(map.energyVolume.max)
        ids[2].value = FloatValue(map.energyDensity.min)
        ids[3].value = FloatValue(map.energyDensity.max)
        ids[4].value = FloatValue(map.complexitySwing.min)
        ids[5].value = FloatValue(map.complexitySwing.max)
        ids[6].value = FloatValue(map.complexityVariation.min)
        ids[7].value = FloatValue(map.complexityVariation.max)
        ids[8].value = FloatValue(map.spaceDecay.min)
        ids[9].value = FloatValue(map.spaceDecay.max)
        ids[10].value = FloatValue(map.moodHarmonics.min)
        ids[11].value = FloatValue(map.moodHarmonics.max)
        ids[12].value = FloatValue(map.moodTimbre.min)
        ids[13].value = FloatValue(map.moodTimbre.max)
    }

    /**
     * Apply per-track overrides defined on the given section. For each track,
     * the effective value is `override?.field ?: tv.field`. Restores base
     * values automatically when the section has no override for that track.
     *
     * @param applyPatternInputs false at vibe load. `density` and the hold parameters are read
     *   only by pattern generation (`load_vibe` and the déjà-vu regeneration), so an opening
     *   section's values would bake into patterns nothing regenerates at a section boundary.
     *   Volume, envelope profile and sends are re-read per render block, so they always land.
     */
    private fun applyTrackOverridesForSection(sectionIndex: Int, applyPatternInputs: Boolean = true) {
        val vibe = vibeFlow.value
        val section = vibe.arrangement?.sections?.getOrNull(sectionIndex) ?: return
        val overrides = section.trackOverrides
        val next = SendBases()
        for (i in 0 until 8) {
            val tv = vibe.tracks.getOrNull(i) ?: continue
            val edm = tv.engineEdm
            val spa = tv.engineSpace
            val o = overrides?.get(i)
            if (applyPatternInputs) {
                trackHoldProbabilityIds[i].value = FloatValue(o?.holdProbability ?: edm.holdProbability)
                trackHoldLengthMinIds[i].value = IntValue(o?.holdLengthMin ?: edm.holdLengthMin)
                trackHoldLengthMaxIds[i].value = IntValue(o?.holdLengthMax ?: edm.holdLengthMax)
                trackHoldProbabilitySpaceIds[i].value = FloatValue(o?.holdProbability ?: spa.holdProbability)
                trackHoldLengthMinSpaceIds[i].value = IntValue(o?.holdLengthMin ?: spa.holdLengthMin)
                trackHoldLengthMaxSpaceIds[i].value = IntValue(o?.holdLengthMax ?: spa.holdLengthMax)
            }
            // NOTE: density is deliberately absent here. It reaches the engine through the
            // per-section table pushed by pushArrangement(), which the C++ resolves
            // synchronously at the section boundary and regenerates from. Writing the
            // effective value into the genre atomic from this 5Hz collector would race that
            // and leave the base density wrong for the next section.
            // Both slots: C++ picks per render block by the live engine_index.
            trackVolumeIds[i].value = FloatValue(o?.volume ?: edm.volume)
            trackVolumeSpaceIds[i].value = FloatValue(o?.volume ?: spa.volume)
            // A section morph only survives if it is pinned: unpinned, the render loop
            // recomputes morph as lerp_macro(space, spaceDecay) every block and the
            // override is never heard. Pin for the override's duration, restore on exit.
            trackMorphIds[i].value = FloatValue(o?.morph ?: edm.morph)
            trackMorphSpaceIds[i].value = FloatValue(o?.morph ?: spa.morph)
            trackPinMorphIds[i].value = IntValue(if (o?.morph != null || edm.pinMorph) 1 else 0)
            trackPinMorphSpaceIds[i].value = IntValue(if (o?.morph != null || spa.pinMorph) 1 else 0)
            trackEnvelopeIds[i].value = IntValue((o?.envelopeProfile ?: tv.envelopeProfile).id)
            next.delayEdm[i] = o?.delaySend ?: edm.delaySend
            next.reverbEdm[i] = o?.reverbSend ?: edm.reverbSend
            next.delaySpace[i] = o?.delaySend ?: spa.delaySend
            next.reverbSpace[i] = o?.reverbSend ?: spa.reverbSend
        }
        sendBases = next
        pushEffectiveSends(deepId.value.asFloat())
    }

    private fun pushEffectiveSends(deep: Float) {
        val vibe = vibeFlow.value
        val space = spaceId.value.asFloat()
        val floor = vibe.effects.deepFloor
        // SPACE boosts DEEP with a per-vibe floor: effectiveDeep = deep * (floor + space * (1 - floor))
        val effectiveDeep = deep * (floor + space * (1.0f - floor))
        val bases = sendBases
        for (i in 0 until 8) {
            trackDelaySendIds[i].value = FloatValue(bases.delayEdm[i] * effectiveDeep)
            trackReverbSendIds[i].value = FloatValue(bases.reverbEdm[i] * effectiveDeep)
            trackDelaySendSpaceIds[i].value = FloatValue(bases.delaySpace[i] * effectiveDeep)
            trackReverbSendSpaceIds[i].value = FloatValue(bases.reverbSpace[i] * effectiveDeep)
        }
    }

    /**
     * Pack and write arrangement data to engine atomics via SynthController.
     *
     * Layout for C++ engine arrays (must match orpheus_engine_routing.cpp and load_vibe()):
     *
     * section_data[s * Arrangement.SECTION_DATA_FIELDS + field]:
     *   0=bars_min, 1=bars_max, 2=bar_step (1 = any; 2 = odd/even only; etc.),
     *   3=recency_decay, 4=transition_count,
     *   5=macro_energy, 6=macro_complexity, 7=macro_space, 8=macro_mood,
     *   9=solo_mode_id (0=no solo), 10=solo_probability, 11=solo_mutation_rate (LickBuilder),
     *   12=solo_lick_influence (Jam), 13=solo_bars_min (LongFill), 14=solo_bars_max (LongFill),
     *   15=reserved (retired: was exitScratchMs, see the zeroing comment below), 16=jamCarry, 17=reserved,
     *   18=comping_style_override (-1=no override), 19=comping_inversion_override, 20=chord_follow_override,
     *   21=weather_rain, 22=weather_rumble, 23=weather_strike_chance, 24=weather_distance,
     *   25=weather_rain_level (SectionWeather; all-zero when [Section.weather] is null),
     *   26=lick_index+1 (0=no override)
     *
     * section_transitions[s * MAX_SECTION_TRANSITIONS * 3 + t * 3 + field]:
     *   0=targetIndex, 1=weight, 2=transitionBars
     *   Stride is edges-per-section, NOT sections — see Arrangement.MAX_SECTION_TRANSITIONS.
     *
     * track_solo_behavior[t * 15 + field]:
     *   0=volume_boost, 1=density_boost, 2=timbre_min, 3=timbre_max,
     *   4=morph_min, 5=morph_max, 6=harmonics_min, 7=harmonics_max,
     *   8=evolution_intensity, 9=fill_probability,
     *   10=rest_probability, 11=hold_probability, 12=density_curve_min,
     *   13=density_curve_max, 14=chromatic_passing
     *
     * track_ducking[t * DuckingProfile.WIRE_FIELDS + field]:
     *   0=volume_reduction, 1=density_reduction, 2=ghost_reduction,
     *   3=fill_suppression, 4=simplify (0/1), 5=reverb_boost,
     *   6=declared (0 = the vibe authored no profile, so the engine keeps its own duck
     *     constants and ignores slots 0-5; all-zero values are a real "do not duck me")
     *
     * track_solo_markov[t * 15 + i] = intervalWeights[i]
     *
     * arrangement_generation written last as acquire fence.
     */
    // Trans-fx has no count/active field of its own (see the writer-contract comment on
    // pulsar_trans_fx_data in orpheus_engine.h) — every apply must zero every unauthored
    // row, on EVERY path through pushArrangement, including the no-arrangement early
    // return below. Unlike section_data, a future reader isn't guaranteed to also check
    // arrangement_active first, so this bank can't lean on that guard the way section
    // fields (including weather) do.
    private fun pushTransFxBank(rows: List<FloatArray>) {
        val transFxData = FloatArray(TransitionFxWire.BANK_SIZE)
        rows.forEachIndexed { rowIdx, row ->
            row.copyInto(transFxData, rowIdx * TransitionFxWire.ROW_FIELDS)
        }
        transFxData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "trans_fx_data_$i"), FloatValue(v)
            )
        }
    }

    private fun pushArrangement(vibe: Vibe) {
        val arr = vibe.arrangement
        if (arr == null) {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "arrangement_active"), IntValue(0)
            )
            // No sections means no edges to stage — zero the bank so a PREVIOUS vibe's
            // rows can't linger (see pushTransFxBank's doc comment above).
            pushTransFxBank(emptyList())
            log.debug { "Arrangement: inactive (vibe=${vibe.name})" }
            return
        }
        log.debug { "Arrangement: pushing ${arr.sections.size} sections for vibe=${vibe.name}" }

        // Scalar arrangement headers
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_active"), IntValue(1)
        )
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_section_count"), IntValue(arr.sections.size)
        )
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_intro_index"), IntValue(arr.introIndex ?: -1)
        )
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_outro_index"), IntValue(arr.outroIndex ?: -1)
        )

        // Trans-fx bank accumulator: flattened across every section/edge below, written
        // once after the loop (see kMaxTransFxRows in pulsar_transition_fx.h).
        val transFxRows = ArrayList<FloatArray>(TransitionFxWire.MAX_ROWS)

        // Section data (Arrangement.SECTION_DATA_FIELDS floats per section)
        arr.sections.forEachIndexed { s, section ->
            val base = s * Arrangement.SECTION_DATA_FIELDS
            val mo = section.macroOverrides
            fun setSection(field: Int, v: Float) =
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_data_${base + field}"), FloatValue(v)
                )
            // Must match C++ load_vibe() unpack order exactly:
            // [0]=bars_min, [1]=bars_max, [2]=bar_step (1 = any value in
            //   [bars_min, bars_max]; 2 = odd-or-even-only stride), [3]=recency_decay,
            // [4]=transition_count, [5]=energy, [6]=complexity, [7]=space, [8]=mood,
            // [9..17]=solo data (format depends on new vs legacy system),
            // [18]=comping_style_override, [19]=comping_inversion_override,
            // [20]=chord_follow_override, [21]=lick_index+1 (0 = no override)
            setSection(0, section.barsMin.toFloat())
            setSection(1, section.barsMax.toFloat())
            setSection(2, section.barStep.toFloat())
            setSection(3, section.recencyDecay)
            setSection(4, section.transitions.size.toFloat())
            setSection(5, mo?.energy ?: -1f)
            setSection(6, mo?.complexity ?: -1f)
            setSection(7, mo?.space ?: -1f)
            setSection(8, mo?.mood ?: -1f)

            val sectionSolo = section.soloMode
            if (sectionSolo != null) {
                val soloModeId = when (sectionSolo) {
                    is SoloMode.LongFill -> 1f
                    is SoloMode.LickBuilder -> 2f
                    is SoloMode.Jam -> 3f
                }
                val soloProbability = when (sectionSolo) {
                    is SoloMode.LongFill -> sectionSolo.probability
                    is SoloMode.LickBuilder -> sectionSolo.probability
                    is SoloMode.Jam -> sectionSolo.probability
                }
                setSection(9, soloModeId)
                setSection(10, soloProbability)
                setSection(11, (sectionSolo as? SoloMode.LickBuilder)?.mutationRate ?: 0.5f)
                setSection(12, (sectionSolo as? SoloMode.Jam)?.lickInfluence ?: 0.5f)
                setSection(13, (sectionSolo as? SoloMode.LongFill)?.barsMin?.toFloat() ?: 2f)
                setSection(14, (sectionSolo as? SoloMode.LongFill)?.barsMax?.toFloat() ?: 4f)
                setSection(15, 0f) // slot 15 retired, never repurpose
                setSection(16, 0f) // slot 16 written below (jamCarry)
                setSection(17, 0f) // reserved
            } else {
                // No solo in this section
                setSection(9, 0f)
                for (slot in 10..17) setSection(slot, 0f)
            }

            // Slot 15 retired (was Section.exitScratchMs) — left zeroed by both branches
            // above; never repurpose.

            // Slot 16: jamCarry — carry an in-flight band solo across this
            // section's entry (see Section.jamCarry). Written after the solo
            // block so it wins over the reserved-0 writes in both branches.
            setSection(16, if (section.jamCarry) 1f else 0f)

            // Section-level comping overrides (slots 18-20); -1.0 = no override
            setSection(18, compingStyleOrSentinel(section.compingStyle))
            setSection(19, compingInversionOrSentinel(section.compingInversion))
            setSection(20, chordFollowOrSentinel(section.chordFollow))

            // Weather bed (slots 21-25); null = all-zero, no storm ambience this section.
            // rainLevel's absent value is 0, not its 1f default: an all-zero row IS the
            // "no weather" encoding, and rate 0 renders nothing at any level anyway.
            val weather = section.weather
            setSection(21, weather?.rain ?: 0f)
            setSection(22, weather?.rumble ?: 0f)
            setSection(23, weather?.strikeChance ?: 0f)
            setSection(24, weather?.distance ?: 0f)
            setSection(25, weather?.rainLevel ?: 0f)
            // [26]=lick_index+1 (0 = no override; the port array is zero-initialised)
            setSection(26, ((section.lickIndex ?: -1) + 1).toFloat())

            // Per-track section overrides. Always write all 8 slots so a vibe
            // reload doesn't carry stale overrides from a previous vibe.
            // -1 = no override (track falls back to its base value or to the
            // section-level override if present).
            for (t in 0 until 8) {
                val o = section.trackOverrides?.get(t)
                val baseIdx = s * 8 + t
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_comping_$baseIdx"),
                    IntValue(o?.compingStyle?.engineId ?: -1)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_inversion_$baseIdx"),
                    IntValue(o?.sectionInversion?.ordinal ?: -1)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_arp_mode_$baseIdx"),
                    IntValue(o?.arpMode?.ordinal ?: -1)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_chord_follow_$baseIdx"),
                    IntValue(o?.chordFollow?.ordinal ?: -1)
                )
                // Density is a float and 0 is meaningful ("track out"), so the no-override
                // sentinel is -1, not 0. The engine resolves this against the vibe's base
                // density at the boundary and regenerates the tracks that changed.
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_density_$baseIdx"),
                    FloatValue(o?.density ?: -1f)
                )
                // Breathe (volume/timbre swell), trailing 3 slots on this per-track family.
                // 0 is the natural "off" value for all three, so absent overrides don't need
                // a separate sentinel the way density does.
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_breathe_bars_$baseIdx"),
                    FloatValue((o?.breatheBars ?: 0).toFloat())
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_breathe_floor_$baseIdx"),
                    FloatValue(o?.breatheFloor ?: 0f)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_breathe_timbre_span_$baseIdx"),
                    FloatValue(o?.breatheTimbreSpan ?: 0f)
                )
            }

            // Section-level exit and entry effects: ONE sentinel row each — EDGE_ANY covers
            // every outgoing edge, EDGE_ENTRY every arrival — not one row per edge. Emitted
            // ahead of the per-edge rows below so they win pending slots first if the
            // MAX_ROWS cap truncates.
            section.exitEffects.forEach { effect ->
                if (transFxRows.size >= TransitionFxWire.MAX_ROWS) return@forEach
                transFxRows.add(
                    floatArrayOf(s.toFloat(), TransitionFxWire.EDGE_ANY) + TransitionFxWire.fieldsFor(effect)
                )
            }
            section.entryEffects.forEach { effect ->
                if (transFxRows.size >= TransitionFxWire.MAX_ROWS) return@forEach
                transFxRows.add(
                    floatArrayOf(s.toFloat(), TransitionFxWire.EDGE_ENTRY) + TransitionFxWire.fieldsFor(effect)
                )
            }

            // Transitions for this section (up to 8 edges × 3 floats per edge:
            // [target_index, weight, transition_bars]).
            val transBase = s * Arrangement.MAX_SECTION_TRANSITIONS * 3
            section.transitions.forEachIndexed { t, tr ->
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 3}"),
                    FloatValue(tr.targetIndex.toFloat())
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 3 + 1}"),
                    FloatValue(tr.weight)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 3 + 2}"),
                    FloatValue(tr.transitionBars.toFloat())
                )
                // Dramatic effects armed on this edge (scratch/tape-stop/strike), collected
                // for the flattened trans_fx_data_$i write after the section loop. Extra
                // rows past MAX_ROWS are dropped, matching every other section-bank's
                // silent-truncation-past-the-wire-cap behavior.
                tr.effects.forEach { effect ->
                    if (transFxRows.size >= TransitionFxWire.MAX_ROWS) return@forEach
                    transFxRows.add(
                        floatArrayOf(s.toFloat(), t.toFloat()) + TransitionFxWire.fieldsFor(effect)
                    )
                }
            }

            // --- Per-section progression override ---
            // Per-chord glide is applied on transition into each chord by the
            // pulsar progression runner; 0 = no glide.
            val cp = section.customProgression
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_progression_active_$s"),
                IntValue(cp?.size ?: 0)
            )
            if (cp != null) {
                for (i in cp.indices) {
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_progression_degree_${s * 12 + i}"),
                        IntValue(cp[i].degree)
                    )
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_progression_glide_${s * 12 + i}"),
                        FloatValue(cp[i].glideRate)
                    )
                }
            }
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_chords_per_bar_$s"),
                IntValue(section.chordsPerBar ?: 0)
            )

            // --- Per-section tension override ---
            val to = section.tensionOverride
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_tension_active_$s"),
                IntValue(if (to != null) 1 else 0)
            )
            if (to != null) {
                // Tension's OWN fixed stride — independent of Arrangement.SECTION_DATA_FIELDS.
                // It never grew the 4 weather slots, so this literal must stay 21.
                val tBase = s * 21
                fun setTension(field: Int, v: Float) =
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_tension_data_${tBase + field}"),
                        FloatValue(v)
                    )
                setTension(0,  to.innerBars.toFloat())
                setTension(1,  to.outerBars.toFloat())
                setTension(2,  to.outerDepth)
                setTension(3,  to.volume)
                setTension(4,  to.timing)
                setTension(5,  if (to.tonal.octaveShift) 1f else 0f)
                setTension(6,  to.tonal.keyShift.toFloat())
                setTension(7,  to.tonal.halfLick.ordinal.toFloat())
                setTension(8,  to.tonal.chromaticPassing)
                setTension(9,  to.evolution.timbreLow)
                setTension(10, to.evolution.timbreHigh)
                setTension(11, to.evolution.timbreProbability)
                setTension(12, to.evolution.morphLow)
                setTension(13, to.evolution.morphHigh)
                setTension(14, to.evolution.morphProbability)
                setTension(15, to.evolution.harmonicsLow)
                setTension(16, to.evolution.harmonicsHigh)
                setTension(17, to.evolution.harmonicsProbability)
                setTension(18, to.evolution.attackPoint)
                setTension(19, to.evolution.releaseSpeed)
                setTension(20, to.spurtChance)
            }

            // --- Per-section comping humanization override ---
            val ch = section.compingHumanization
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_comping_humanization_active_$s"),
                IntValue(if (ch != null) 1 else 0)
            )
            if (ch != null) {
                val chBase = s * 4
                fun setCh(field: Int, v: Float) =
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_comping_humanization_data_${chBase + field}"),
                        FloatValue(v)
                    )
                setCh(0, ch.dropProbability)
                setCh(1, ch.ghostProbability)
                setCh(2, ch.octaveJumpProbability)
                setCh(3, ch.extensionProbability)
            }
        }

        // Trans-fx bank: flatten the accumulated rows and zero-fill the rest so a vibe
        // reload doesn't carry stale rows from a previous vibe.
        pushTransFxBank(transFxRows)

        // Band solo config
        val bandConfig = vibe.band
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "band_active"),
            IntValue(if (bandConfig != null) 1 else 0)
        )
        if (bandConfig != null) {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "band_member_count"),
                IntValue(bandConfig.members.size)
            )
            bandConfig.members.forEachIndexed { m, member ->
                val base = m * 12
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 0}"),
                    FloatValue(member.tracks.size.toFloat())
                )
                member.tracks.forEachIndexed { t, trackIdx ->
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "band_member_data_${base + 1 + t}"),
                        FloatValue(trackIdx.toFloat())
                    )
                }
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 9}"),
                    FloatValue(if (member.alwaysActive) 1f else 0f)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 10}"),
                    FloatValue(member.loudness)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 11}"),
                    FloatValue(member.creativity)
                )
            }
            val N = bandConfig.members.size
            for (i in 0 until minOf(bandConfig.handoffMatrix.size, N * N)) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_handoff_$i"),
                    FloatValue(bandConfig.handoffMatrix[i])
                )
            }
            for (i in 0 until minOf(bandConfig.pullInMatrix.size, N * N)) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_pull_in_$i"),
                    FloatValue(bandConfig.pullInMatrix[i])
                )
            }
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_pull_in_bars_min"), IntValue(bandConfig.pullInBarsMin))
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_pull_in_bars_max"), IntValue(bandConfig.pullInBarsMax))
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_bars_per_lead_min"), IntValue(bandConfig.barsPerLeadMin))
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_bars_per_lead_max"), IntValue(bandConfig.barsPerLeadMax))
        }

        // Per-track solo behavior and ducking (use track's own config if present,
        // else use defaults derived from the track's envelope profile)
        vibe.tracks.forEachIndexed { t, tv ->
            val behavior = tv.soloBehavior ?: defaultSoloBehavior(tv.envelopeProfile)
            val markov = behavior.markovConfig
            val behaviorBase = t * 15
            fun setSolo(field: Int, v: Float) =
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "track_solo_behavior_${behaviorBase + field}"),
                    FloatValue(v)
                )
            setSolo(0, behavior.volumeBoost)
            setSolo(1, behavior.densityBoost)
            setSolo(2, behavior.timbreMin)
            setSolo(3, behavior.timbreMax)
            setSolo(4, behavior.morphMin)
            setSolo(5, behavior.morphMax)
            setSolo(6, behavior.harmonicsMin)
            setSolo(7, behavior.harmonicsMax)
            setSolo(8, behavior.evolutionIntensity)
            setSolo(9, behavior.fillProbability)
            setSolo(10, markov?.restProbability ?: 0.15f)
            setSolo(11, markov?.holdProbability ?: 0.2f)
            setSolo(12, markov?.densityCurveMin ?: 0.4f)
            setSolo(13, markov?.densityCurveMax ?: 0.8f)
            setSolo(14, markov?.chromaticPassing ?: 0.1f)

            // Markov interval weights (15 floats)
            val markovBase = t * 15
            val weights = markov?.intervalWeights
            for (i in 0 until 15) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "track_solo_markov_${markovBase + i}"),
                    FloatValue(weights?.getOrNull(i) ?: (1f / 15f))
                )
            }

            // Ducking profile. Slot 6 gates slots 0-5: without an authored profile the
            // engine keeps its own duck constants, so these values stay inert.
            val ducking = tv.duckingProfile ?: defaultDuckingProfile(tv.envelopeProfile)
            val duckBase = t * DuckingProfile.WIRE_FIELDS
            fun setDuck(field: Int, v: Float) =
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "track_ducking_${duckBase + field}"),
                    FloatValue(v)
                )
            setDuck(0, ducking.volumeReduction)
            setDuck(1, ducking.densityReduction)
            setDuck(2, ducking.ghostReduction)
            setDuck(3, ducking.fillSuppression)
            setDuck(4, if (ducking.simplify) 1f else 0f)
            setDuck(5, ducking.reverbBoost)
            setDuck(6, if (tv.duckingProfile != null) 1f else 0f)
        }
        // No trailing zero-fill needed: Vibe.init requires exactly 8 tracks, so this loop
        // rewrites every row of all three banks and no previous vibe's row can survive.

        // Void Anomaly config bank (absent => probability 0 = auto-firing disabled).
        val va = vibe.anomalies.filterIsInstance<VoidAnomaly>().firstOrNull()
        val voidData = floatArrayOf(
            va?.probability ?: 0f,
            va?.floorLevel ?: 0.05f,
            va?.rampDownBars ?: 1f,
            va?.floorBarsMin ?: 1f,
            va?.floorBarsMax ?: 2f,
            va?.rampUpBars ?: 1.5f,
            va?.ghostIntensity ?: 0.5f,
            if (va != null) 1f else 0f, // [7] declared flag — manual trigger fires only on declaring vibes
        )
        voidData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "void_data_$i"), FloatValue(v)
            )
        }

        // Wah Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val wa = vibe.anomalies.filterIsInstance<WahAnomaly>().firstOrNull()
        val wahData = floatArrayOf(
            wa?.probability ?: 0f,
            wa?.durationBarsMin ?: 2f,
            wa?.durationBarsMax ?: 4f,
            wa?.voice?.rateDivision ?: 8f,
            wa?.voice?.depth ?: 1f,
            wa?.voice?.resonanceQ ?: 3f,
            wa?.voice?.centerHz ?: 800f,
            wa?.voice?.sweepOctaves ?: 1.3f,
            wa?.voice?.wet ?: 1f,
            if (wa != null) 1f else 0f, // [9] declared flag — manual trigger fires only on declaring vibes
        )
        wahData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "wah_data_$i"), FloatValue(v)
            )
        }

        // Crossfade Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val ca = vibe.anomalies.filterIsInstance<CrossfadeAnomaly>().firstOrNull()
        val crossfadeData = floatArrayOf(
            ca?.probability ?: 0f,
            ca?.durationBarsMin ?: 1f,
            ca?.durationBarsMax ?: 2f,
            ca?.depth ?: 0f,
            if (ca != null) 1f else 0f, // [4] declared flag — manual trigger fires only on declaring vibes
        )
        crossfadeData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "crossfade_data_$i"), FloatValue(v)
            )
        }

        // Swell Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val sw = vibe.anomalies.filterIsInstance<SwellAnomaly>().firstOrNull()
        val swellData = floatArrayOf(
            sw?.probability ?: 0f,
            sw?.durationBarsMin ?: 2f,
            sw?.durationBarsMax ?: 4f,
            sw?.startLevel ?: 1f,
            sw?.peakLevel ?: 1.3f,
            if (sw != null) 1f else 0f, // [5] declared flag — manual trigger fires only on declaring vibes
        )
        swellData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "swell_data_$i"), FloatValue(v)
            )
        }

        // Cut Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val cu = vibe.anomalies.filterIsInstance<CutAnomaly>().firstOrNull()
        val cutData = floatArrayOf(
            cu?.probability ?: 0f,
            cu?.durationBarsMin ?: 1f,
            cu?.durationBarsMax ?: 2f,
            cu?.gateRate ?: 2f,
            cu?.duty ?: 0.5f,
            cu?.depth ?: 0f,
            if (cu != null) 1f else 0f, // [6] declared flag — manual trigger fires only on declaring vibes
        )
        cutData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "cut_data_$i"), FloatValue(v)
            )
        }

        // Tape Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val ta = vibe.anomalies.filterIsInstance<TapeAnomaly>().firstOrNull()
        val tapeData = floatArrayOf(
            ta?.probability ?: 0f,
            ta?.durationBarsMin ?: 1f,
            ta?.durationBarsMax ?: 2f,
            if (ta != null) 1f else 0f, // [3] declared flag — manual trigger fires only on declaring vibes
        )
        tapeData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "tape_data_$i"), FloatValue(v)
            )
        }

        // Scratch Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val sc = vibe.anomalies.filterIsInstance<ScratchAnomaly>().firstOrNull()
        val scratchData = floatArrayOf(
            sc?.probability ?: 0f,
            sc?.durationBarsMin ?: 1f,
            sc?.durationBarsMax ?: 2f,
            if (sc != null) 1f else 0f, // [3] declared flag — manual trigger fires only on declaring vibes
        )
        scratchData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "scratch_data_$i"), FloatValue(v)
            )
        }

        // Filter Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val fi = vibe.anomalies.filterIsInstance<FilterAnomaly>().firstOrNull()
        val filterData = floatArrayOf(
            fi?.probability ?: 0f,
            fi?.durationBarsMin ?: 2f,
            fi?.durationBarsMax ?: 4f,
            if (fi != null) 1f else 0f, // [3] declared flag — manual trigger fires only on declaring vibes
        )
        filterData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "filter_data_$i"), FloatValue(v)
            )
        }

        // Storm Anomaly config bank (absent => probability 0 = auto-firing disabled).
        // Order MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        val sto = vibe.anomalies.filterIsInstance<StormAnomaly>().firstOrNull()
        val stormData = floatArrayOf(
            sto?.probability ?: 0f,
            sto?.durationBarsMin?.toFloat() ?: 1f,
            sto?.durationBarsMax?.toFloat() ?: 2f,
            sto?.intensity ?: 0.7f,
            sto?.distance ?: 0.4f,
            if (sto != null) 1f else 0f, // [5] declared flag — manual trigger fires only on declaring vibes
        )
        stormData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "storm_data_$i"), FloatValue(v)
            )
        }

        // Per-track lick-wah insert config bank. NOT an anomaly — a standing per-track filter.
        // [0] = track opt-in bitmask (bit t set => track t filters through its own wah voice).
        // Then WahParams.FIELDS floats per track at 1 + t * FIELDS, in WahParams declaration
        // order: rateDivision, depth, resonanceQ, centerHz, sweepOctaves, wet. Order and stride
        // MUST match the C++ unpack in orpheus_unit_pulsar.cpp load_vibe.
        //
        // Resolution per track: wahLick opts in, TrackRole.Melodic.wahParams voices it, and a
        // null falls back to the vibe-wide Vibe.lickWah. A track that opts in with no params
        // available either way stays OUT of the mask, so the insert is inert (as before) rather
        // than running a default filter nobody asked for.
        var lickWahMask = 0
        val lickWahData = FloatArray(1 + vibe.tracks.size * WahParams.FIELDS)
        vibe.tracks.forEachIndexed { t, tv ->
            val melodic = tv.role as? TrackRole.Melodic
            val p = if (melodic?.wahLick == true) (melodic.wahParams ?: vibe.lickWah) else null
            if (p != null) {
                lickWahMask = lickWahMask or (1 shl t)
                val base = 1 + t * WahParams.FIELDS
                lickWahData[base] = p.rateDivision
                lickWahData[base + 1] = p.depth
                lickWahData[base + 2] = p.resonanceQ
                lickWahData[base + 3] = p.centerHz
                lickWahData[base + 4] = p.sweepOctaves
                lickWahData[base + 5] = p.wet
            }
        }
        lickWahData[0] = lickWahMask.toFloat()
        lickWahData.forEachIndexed { i, v ->
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "lick_wah_data_$i"), FloatValue(v)
            )
        }

        // Write arrangement generation last as release fence (triggers C++ load_vibe re-read)
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_generation"),
            IntValue(1)
        )
    }

    /**
     * [PulsarFeature.applyNotatedScore]: resolves [id] against [notatedScoresById] and loads
     * that provider's asset off the UI caller's stack, pushing it once ready. `null` or an
     * unmatched id clears instead -- see [PulsarFeature.applyNotatedScore]'s KDoc for why
     * that clear is required, not optional. `scope` outlives any single caller (see
     * [FeatureCoroutineScope]'s KDoc), so this is safe to fire from a click handler that
     * won't itself stick around.
     *
     * A load failure (missing asset, malformed JSON, a failed `require` in `NotatedScore`'s
     * init) is caught and logged rather than left to crash `scope`'s uncaught-exception path
     * (it has no [kotlinx.coroutines.CoroutineExceptionHandler] -- see
     * [FeatureCoroutineScope]) -- and clears the track rather than leaving a stale score
     * pushed, so a bad asset can't strand a previous piece's notes under the new vibe.
     * [CancellationException] is deliberately excluded from "load failure" and rethrown --
     * swallowing it here would break structured concurrency if this coroutine is ever
     * cancelled mid-load (not true today, since [FeatureCoroutineScope] lives for the
     * process, but its own KDoc leaves the door open for tests and future teardown).
     */
    override fun applyNotatedScore(id: String?) {
        val provider = id?.let { notatedScoresById[it] }
        if (id != null && provider == null) {
            log.warn { "No NotatedScoreProvider registered for id '$id'; clearing score-driven tracks" }
        }
        // Remember only what actually resolved, so an unmatched id doesn't get re-pushed
        // (and re-warned) on every later vibe re-apply.
        notatedScoreIdFlow.value = if (provider != null) id else null
        scope.launch {
            val score = try {
                provider?.score()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Failed to load notated score '$id'; clearing score-driven tracks" }
                null
            }
            pushNotatedScore(score)
        }
    }

    /**
     * Uploads a notated score, or clears one when [score] is null.
     *
     * Pitch and velocity share one write as 7-bit fields, halving the wire traffic for
     * what is by far the largest payload this ViewModel pushes.
     *
     * Write order IS the publish contract, because the audio thread reads the score event
     * arrays every block rather than only on a generation change. Per track: clear
     * `score_driven_` (unpublishing the array before it is overwritten), write the events,
     * then the count that bounds them, then set `score_driven_` again. `score_generation`
     * goes LAST for the whole push, matching how arrangement_generation works -- it is what
     * arms the C++ side and resets the score clock. Writing any flag before its data lets
     * the engine act on a half-written array; `PulsarScorePushTest` pins the order.
     */
    fun pushNotatedScore(score: NotatedScore?) {
        val parts = score?.parts?.associateBy { it.trackIndex }.orEmpty()
        fun put(symbol: String, v: Int) = synthController.setPluginControl(
            PluginControlId(PULSAR_URI, symbol), IntValue(v)
        )
        for (t in 0..7) {
            put("score_driven_$t", 0)
            val part = parts[t]
            if (part == null) {
                put("score_count_$t", 0)
                continue
            }
            val base = t * NotatedScore.MAX_SCORE_EVENTS * 4
            val pt = part.timbre
            putF("score_part_${t}_engine", pt.engineIndex.toFloat())
            putF("score_part_${t}_harmonics", pt.harmonics)
            putF("score_part_${t}_timbre", pt.timbre)
            putF("score_part_${t}_morph", pt.morph)
            putF("score_part_${t}_decay", pt.decay)
            putF("score_part_${t}_level", pt.level)
            part.events.forEachIndexed { i, e ->
                val o = base + i * 4
                put("score_ev_${o + 0}", e.tick)
                // Wire field is uint16_t; a raw static_cast truncates rather than clamps
                // (65536 -> 0), so an out-of-range value would silently corrupt the duration
                // instead of just capping it. Coerce here so marshalling can't produce that.
                put("score_ev_${o + 1}", e.durationTicks.coerceAtMost(65535))
                put("score_ev_${o + 2}", e.pitch or (e.velocity shl 7))
                put("score_ev_${o + 3}", (if (e.hold) 1 else 0) or (if (e.bandRelease) 2 else 0))
            }
            put("score_count_$t", part.events.size)
            put("score_driven_$t", 1)
        }
        put("score_generation", ++scoreGeneration)
        _notatedScoreFlow.value = score
    }

    // Float-typed twin of pushNotatedScore's local `put`, shared with setScorePartControl so
    // a live orchestration-panel edit writes through the identical symbol format.
    private fun putF(symbol: String, v: Float) = synthController.setPluginControl(
        PluginControlId(PULSAR_URI, symbol), FloatValue(v)
    )

    /** [PulsarFeature.setScorePartControl]: writes straight through, no local state to mirror. */
    override fun setScorePartControl(track: Int, field: String, value: Float) {
        putF("score_part_${track}_$field", value)
    }

    /** [PulsarFeature.setBandHold]: writes straight through, no local state to mirror. */
    override fun setBandHold(held: Boolean) {
        synthController.setPluginControl(PulsarSymbol.BAND_HOLD.controlId, IntValue(if (held) 1 else 0))
    }

    private var scoreGeneration = 0

    private fun defaultSoloBehavior(profile: EnvelopeProfile): SoloBehavior = when (profile) {
        EnvelopeProfile.RHYTHM -> SoloBehavior(
            fillProbability = 0.8f, densityBoost = 0.4f,
            markovConfig = SoloMarkovConfig.RHYTHMIC_DEFAULT,
        )
        EnvelopeProfile.MELODIC -> SoloBehavior(
            markovConfig = SoloMarkovConfig.MELODIC_DEFAULT,
        )
        EnvelopeProfile.EFFECT -> SoloBehavior(
            harmonicsMin = 0.1f, harmonicsMax = 0.9f,
            markovConfig = SoloMarkovConfig.EFFECT_DEFAULT,
        )
        EnvelopeProfile.WILD -> SoloBehavior(
            volumeBoost = 0.3f, evolutionIntensity = 1.5f,
            markovConfig = SoloMarkovConfig.WILD_DEFAULT,
        )
        EnvelopeProfile.DRONE -> SoloBehavior(
            markovConfig = SoloMarkovConfig.MELODIC_DEFAULT,
        )
    }

    // Value slots for a track that authored no profile. Inert — the declared flag is 0, so
    // the engine keeps its own duck constants. Every field is spelled out rather than
    // inherited so correcting DuckingProfile's defaults could not silently move this table.
    private fun defaultDuckingProfile(profile: EnvelopeProfile): DuckingProfile = when (profile) {
        EnvelopeProfile.RHYTHM -> DuckingProfile(
            volumeReduction = 0.2f, densityReduction = 0.5f,
            ghostReduction = 0.7f, fillSuppression = 0.9f,
            simplify = true, reverbBoost = 0.1f,
        )
        EnvelopeProfile.MELODIC, EnvelopeProfile.DRONE -> DuckingProfile(
            volumeReduction = 0.3f, densityReduction = 0.4f,
            ghostReduction = 0.5f, fillSuppression = 0.8f,
            simplify = true, reverbBoost = 0.1f,
        )
        EnvelopeProfile.EFFECT -> DuckingProfile(
            volumeReduction = 0.4f, densityReduction = 0.6f,
            ghostReduction = 0.5f, fillSuppression = 0.8f,
            simplify = false, reverbBoost = 0.15f,
        )
        EnvelopeProfile.WILD -> DuckingProfile(
            volumeReduction = 0.5f, densityReduction = 0.7f,
            ghostReduction = 0.5f, fillSuppression = 0.95f,
            simplify = true, reverbBoost = 0.1f,
        )
    }

    private fun compingStyleOrSentinel(s: CompingStyle?): Float =
        s?.engineId?.toFloat() ?: -1.0f

    private fun compingInversionOrSentinel(inv: SectionInversion?): Float =
        inv?.ordinal?.toFloat() ?: -1.0f

    private fun chordFollowOrSentinel(cf: ChordFollow?): Float =
        cf?.ordinal?.toFloat() ?: -1.0f

    companion object {
        /**
         * How long the VIBE dropdown stays tinted after the manual anomaly
         * trigger fires. The anomaly's actual active window lives in C++ and
         * isn't surfaced back to Kotlin, so this is an approximation of the
         * arm-latency + arc — just long enough to visibly confirm the
         * trigger landed.
         */
        private val ANOMALY_HIGHLIGHT = 8.seconds

        private val previewVibe = Vibe(
            name = "Preview",
            bpm = 120f,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0f,
                noteRangeLow = 36,
                noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.SPARSE.density,
            ),
            tracks = List(8) {
                val engine = OrpheusEngine(engineId = OrpheusEngineId.VA)
                TrackVoice(
                    engineEdm = engine,
                    engineSpace = engine,
                    role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
                )
            },
        )

        fun previewFeature(state: PulsarUiState = PulsarUiState(
            vibe = previewVibe,
        )): PulsarFeature =
            object : PulsarFeature {
                override val vibeList: List<Vibe> = listOf(previewVibe)
                override val vibeFlow: StateFlow<Vibe> = MutableStateFlow(previewVibe)
                override fun applyVibe(vibe: Vibe) {}
                override val arrangementStateFlow: StateFlow<PulsarArrangementState> = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
                override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(state)
                override val actions: PulsarPanelActions = PulsarPanelActions.EMPTY
            }

        @Composable
        fun feature(): PulsarFeature =
            synthFeature<PulsarFeature>()
    }
}
