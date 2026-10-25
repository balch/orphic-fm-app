package org.balch.orpheus.djapp.ai.tools

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.djapp.ai.collab.CollabEvents
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.DjVibeApplyGuard
import org.balch.orpheus.djapp.ai.collab.MixChangeStore
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixHistory
import org.balch.orpheus.djapp.ai.collab.NoMixChangeStore
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

internal fun vibe(name: String) = Vibe(
    name = name,
    tracks = List(8) { TrackVoice(engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA), engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA)) },
    bpm = 120f,
    rootNote = RootNote.A,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(swingAmount = 0f, ghostProbability = 0f, noteRangeLow = 36, noteRangeHigh = 72, rhythmDensity = RhythmPattern.SPARSE.density),
    arrangement = Arrangement(sections = listOf(Section("intro"), Section("verse"), Section("chorus"))),
)

/** Just enough Pulsar for the controller and tools: knob writes move [stateFlow], as the real knobs do. */
internal class FakePulsar(override val vibeList: List<Vibe>) : PulsarFeature {
    override val vibeFlow = MutableStateFlow(vibeList.first())
    override val vibeNames = vibeList.map { it.name }
    override val vibeLoadCount = MutableStateFlow(0)
    val arrangement = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
    override val arrangementStateFlow: StateFlow<PulsarArrangementState> = arrangement
    override val stateFlow = MutableStateFlow(PulsarUiState(vibe = vibeList.first()))
    val macroWrites = mutableListOf<Pair<String, Float>>()
    override val actions = PulsarPanelActions(
        setEnergy = { macroWrites += "energy" to it; stateFlow.value = stateFlow.value.copy(energy = it) },
        setComplexity = { macroWrites += "complexity" to it; stateFlow.value = stateFlow.value.copy(complexity = it) },
        setSpace = { macroWrites += "space" to it; stateFlow.value = stateFlow.value.copy(space = it) },
        setMood = { macroWrites += "mood" to it; stateFlow.value = stateFlow.value.copy(mood = it) },
    )

    /** Runs before each apply lands, so a test can race another load in; clear it inside to run once. */
    var beforeApply: ((Vibe) -> Unit)? = null

    override fun applyVibe(vibe: Vibe) {
        beforeApply?.invoke(vibe)
        vibeFlow.value = vibe
        benchMacros = emptyMap()
        vibeLoadCount.value++
    }

    /** Engine-only section macros, as the controller last set them. */
    var benchMacros: Map<Macro, Float> = emptyMap()
        private set

    override fun setBenchMacros(values: Map<Macro, Float>) {
        benchMacros = values
    }

    override fun applyVibeByName(name: String): Boolean {
        applyVibe(vibeList.firstOrNull { it.name == name } ?: return false)
        return true
    }

    fun section(s: Int) {
        arrangement.value = PulsarArrangementState(s, 0, 0, false, -1, 0)
    }
}

internal class OneDispatcher(d: CoroutineDispatcher) : DispatcherProvider {
    override val main = d
    override val io = d
    override val default = d
    override val unconfined = d
}

/** Counts captures; [fail] makes each one throw, which must never block the wipe it precedes. */
internal class CountingCapture(var fail: Boolean = false) : MixCaptureService {
    var calls = 0
        private set

    override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>): CaptureOutcome {
        calls++
        if (fail) error("disk full")
        return CaptureOutcome(true, "ok", "/tmp/capture.json", emptyList(), null, aiVibe = false)
    }
}

/** Change sets kept in memory; one saved for a vibe that no longer equals the loaded one reads as none. */
internal class MemoryChangeStore : MixChangeStore {
    val sets = mutableMapOf<String, Pair<Vibe, MixHistory>>()

    override fun get(vibe: Vibe): MixHistory? {
        val (saved, history) = sets[vibe.name] ?: return null
        if (saved == vibe) return history
        sets.remove(vibe.name)
        return null
    }

    override fun put(vibe: Vibe, history: MixHistory) {
        if (history.items.isEmpty()) sets.remove(vibe.name) else sets[vibe.name] = vibe to history
    }

    override fun remove(vibeName: String) {
        sets.remove(vibeName)
    }
}

/** The collaboration layer as the app wires it over [controller], with the Mixer on screen. */
internal class CollabRig(
    pulsar: PulsarFeature,
    controller: () -> MixBenchController,
    scope: CoroutineScope,
    val capture: CountingCapture = CountingCapture(),
    nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    val changeStore: MixChangeStore = NoMixChangeStore(),
    captureService: () -> MixCaptureService = { capture },
) {
    val collab = MixCollab().apply { setMixerVisible(true) }
    val events = CollabEvents()
    val mixer = CollabMixer(controller, { pulsar }, collab, scope, nowMs, { changeStore }, captureService)
    val guard = DjVibeApplyGuard(collab, mixer, events)
}
