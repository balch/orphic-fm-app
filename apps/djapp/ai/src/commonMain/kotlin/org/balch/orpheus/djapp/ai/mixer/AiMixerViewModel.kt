package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.runtime.Composable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.CollabResult
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.VibeEditResult
import org.balch.orpheus.djapp.ai.tools.MixCaptureTool
import org.balch.orpheus.djapp.ai.tools.VIBE_LOADING
import org.balch.orpheus.djapp.ai.usage.AiCostTotals
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SendEdit
import org.balch.orpheus.features.pulsar.bench.macroView
import org.balch.orpheus.features.pulsar.bench.enginePath
import org.balch.orpheus.features.pulsar.bench.lockPath
import org.balch.orpheus.features.pulsar.bench.slotAt
import org.balch.orpheus.features.pulsar.bench.vibeDefault
import org.balch.orpheus.features.pulsar.models.SlotLock

/** The VM's own overlay on the controller-derived state. */
private data class Local(
    val capture: MixerCapture? = null,
    val capturing: Boolean = false,
    val notice: String? = null,
)

/** The VM's overlay with the shared collaboration marks: what the user pinned and what the agent touched. */
private data class Overlay(val local: Local, val pins: Set<Int>, val agentTouched: Set<Int>, val hist: Hist)

/** The History timeline as the panel shows it. */
private data class Hist(val rows: List<HistoryRow>, val hearing: Int?, val onCount: Int, val loadedAtMs: Long, val edited: Set<String>)

/** One voice knob on one slot: the key its pending value is held under until the edit queue reaches it. */
private data class VoiceKey(val track: Int, val slot: EngineSlot, val knob: EngineKnob)

/**
 * The mixer panel's state and edits over the shared [MixBenchController]. Every edit goes through [CollabMixer] as
 * [EditOrigin.USER]. The controller is resolved lazily (flows, edit consumer), never in the constructor, to avoid
 * the DI cycle [org.balch.orpheus.djapp.ai.tools.MixReadTool] documents.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(AiMixerFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<AiMixerFeature>())
class AiMixerViewModel(
    private val controllerProvider: () -> MixBenchController,
    private val pulsarFeature: PulsarFeature,
    private val collabMixer: CollabMixer,
    private val collab: MixCollab,
    private val scope: FeatureCoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val costTotals: AiCostTotals,
) : AiMixerFeature {
    private val log = logging("AiMixerViewModel")
    private val local = MutableStateFlow(Local())
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // A drag sends a value per frame and each vibe edit re-encodes the vibe, so only the newest per knob is applied.
    // A null value is a reset to the file.
    private val pendingVoice = MutableStateFlow<Map<VoiceKey, Float?>>(emptyMap())

    init {
        scope.launch(dispatchers.default) {
            for (edit in edits) {
                // One bad edit must not end the consumer: the scope is a SupervisorJob, so later edits would drop silently.
                try {
                    edit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "mixer edit failed" }
                }
            }
        }
    }

    override val stateFlow: StateFlow<AiMixerUiState> = flow {
        val c = controllerProvider()
        val sections = pulsarFeature.arrangementStateFlow.map { it.sectionIndex }.distinctUntilChanged()
        val knobs = pulsarFeature.stateFlow
            .map { Macro.entries.associateWith(pulsarFeature::macroKnob) }
            .distinctUntilChanged()
        val bench = combine(c.layers, c.scope, c.muted, c.soloed, c.loopSection) { layers, sc, muted, soloed, loop ->
            Bench(layers, sc, muted, soloed, loop)
        }
        val hist = combine(collabMixer.history, collabMixer.hearing, pulsarFeature.vibeFlow) { h, hearing, vibe ->
            val rows = historyRows(h, vibe) { BenchMeters.CHANNEL_NAMES.getOrElse(it) { n -> "T$n" } }
            Hist(rows, hearing, h.onCount + h.vibeEdits.count { it.on }, h.loadedAtMs, h.vibeEdits.filter { it.on }.map { it.path }.toSet())
        }
        val overlay = combine(local, collab.pins, collab.agentTouched, hist, ::Overlay)
        // The slot playing, followed with the engine's own hold between 0.4 and 0.6 energy.
        var heard: EngineSlot? = null
        emitAll(
            combine(bench, pulsarFeature.vibeFlow, sections, knobs, overlay) { b, vibe, section, k, o ->
                val energy = b.layers.macroView(vibe, section, Macro.ENERGY, k[Macro.ENERGY] ?: Macro.ENERGY.vibeDefault(vibe)).heard
                val slot = slotAt(energy, heard ?: if (energy >= 0.5f) EngineSlot.EDM else EngineSlot.SPACE)
                heard = slot
                val voice = VoiceView(slot, o.hist.edited)
                mixerState(vibe, section, b.layers, b.scope, b.muted, b.soloed, b.loop, k, voice).copy(
                    capture = o.local.capture,
                    capturing = o.local.capturing,
                    notice = o.local.notice,
                    pins = o.pins,
                    agentTouched = o.agentTouched,
                    history = o.hist.rows,
                    hearing = o.hist.hearing,
                    onCount = o.hist.onCount,
                    loadedAtMs = o.hist.loadedAtMs,
                )
            }.combine(costTotals.songs) { s, songs -> s.copy(aiCostUsd = songs[s.vibeName]) },
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(scope, SynthFeature.sharingStrategy, AiMixerUiState())

    override val meters: Flow<MeterSnapshot> = flow {
        val c = controllerProvider()
        while (true) {
            emit(c.readMeters())
            delay(METER_POLL_MS)
        }
    }.flowOn(dispatchers.default)

    override val agentEdits: Flow<MixControl> = collab.agentEdits

    override val actions = AiMixerActions(
        setLevelDb = { t, db -> edit(MixControl.Level(t), onDone = ::refuse) { setHeardLevelDb(t, db.coerceIn(MIN_DB, MAX_DB)) } },
        setPan = { t, p -> edit(MixControl.Pan(t), onDone = ::refuse) { setPan(t, p) } },
        setReverb = { t, v -> edit(MixControl.Send(t, reverb = true), onDone = ::sendNotice) { setHeardSend(t, v, reverb = true) } },
        setDelay = { t, v -> edit(MixControl.Send(t, reverb = false), onDone = ::sendNotice) { setHeardSend(t, v, reverb = false) } },
        toggleMute = { t -> edit(MixControl.Mute(t), onDone = ::refuse) { toggleMute(t) } },
        toggleSolo = { t -> edit(MixControl.Solo(t), onDone = ::refuse) { toggleSolo(t) } },
        setMacro = { m, v -> edit(MixControl.MacroKnob(m), onDone = ::refuse) { setMacro(m, v) } },
        setScope = { s ->
            edit(
                MixControl.EditScope,
                // The zero-send notice asks for section scope, so a scope change answers it.
                onDone = { changed -> if (changed) local.update { if (it.notice == ZERO_SEND) it.copy(notice = null) else it } },
            ) {
                val before = scope.value
                setScope(s)
                scope.value != before
            }
        },
        // The jump lands at the next bar line, so the move shows the section it is going to.
        jumpTo = { s -> edit(MixControl.Section, shown = sectionName(s)) { jumpTo(s) } },
        setLoop = { on -> edit(MixControl.Loop) { setLoop(on) } },
        reset = { edit(MixControl.ClearAll, shown = "file") { resetToFile(keep = collab.pins.value) } },
        capture = ::capture,
        dismissNotice = { local.update { it.copy(notice = null) } },
        togglePin = collab::togglePin,
        dismissCapture = { local.update { it.copy(capture = null) } },
        toggleChange = { id ->
            queue {
                val isVibeRow = stateFlow.value.history.any { it is VibeEditRow && it.id == id }
                if (!collabMixer.toggleChange(id) && isVibeRow) local.update { it.copy(notice = VIBE_ROW_REFUSED) }
            }
        },
        markPoint = { queue { collabMixer.markPoint() } },
        hear = { id -> queue { collabMixer.hear(id) } },
        endHear = { queue { collabMixer.endHear() } },
        rollBack = { id -> queue { collabMixer.rollBack(id) } },
        setVoiceKnob = ::setVoiceKnob,
        setSlotLock = ::setSlotLock,
        setEngine = ::setEngine,
    )

    // An equal pair moves together, so the pick is heard whichever slot plays; a split track keeps the other slot's engine.
    private fun setEngine(track: Int, slot: EngineSlot, engine: OrpheusEngineId) {
        val t = stateFlow.value.tracks.getOrNull(track) ?: return
        val slots = if (t.edmEngineId == t.spaceEngineId) EngineSlot.entries else listOf(slot)
        editEngines(slots.map { enginePath(track, it) to JsonPrimitive(engine.name) })
    }

    private fun setSlotLock(track: Int, lock: SlotLock) {
        editEngines(listOf(lockPath(track) to JsonPrimitive(lock.name)), LOCK_UNCHANGED)
    }

    /** Engine picks and locks: vibe-wide rows that play on, whatever section the knobs are scoped to. */
    private fun editEngines(edits: List<Pair<String, JsonPrimitive>>, refused: String = ENGINE_UNCHANGED) = queue {
        when (collabMixer.editVibe(EditOrigin.USER, edits)) {
            is VibeEditResult.Applied -> {
                clearNotice()
                if (controllerProvider().scope.value == Scope.SECTION) local.update { it.copy(notice = ENGINES_VIBE_WIDE) }
            }
            is VibeEditResult.Refused, is VibeEditResult.Invalid -> local.update { it.copy(notice = refused) }
        }
    }

    // Under AUTO the knobs are off: the slot they would tune is not held, so a turn writes nothing.
    private fun setVoiceKnob(track: Int, knob: EngineKnob, value: Float?) {
        val t = stateFlow.value.tracks.getOrNull(track)?.takeIf { it.knobsLive } ?: return
        val slot = t.slot
        pendingVoice.update { it + (VoiceKey(track, slot, knob) to value) }
        queue(::applyVoiceKnobs)
    }

    // Takes every pending knob at once; the queued calls after the first find nothing left.
    private suspend fun applyVoiceKnobs() {
        val batch = pendingVoice.getAndUpdate { emptyMap() }
        for ((key, value) in batch) {
            when (val r = collabMixer.editEngineKnob(EditOrigin.USER, key.track, key.slot, key.knob, value)) {
                is VibeEditResult.Applied -> clearNotice()
                is VibeEditResult.Refused -> local.update { it.copy(notice = VIBE_LOADING) }
                is VibeEditResult.Invalid -> log.warn { "voice knob ${key.knob} on track ${key.track}: ${r.message}" }
            }
        }
    }

    private fun sectionName(index: Int) = pulsarFeature.vibeFlow.value.arrangement?.sections?.getOrNull(index)?.name

    private fun queue(block: suspend () -> Unit) {
        edits.trySend(block)
    }

    /** Queues a user edit; [onDone] gets what the controller returned. A user edit is never refused. */
    private fun <T> edit(
        control: MixControl,
        shown: String? = null,
        onDone: (T) -> Unit = {},
        block: suspend MixBenchController.() -> T,
    ) = queue {
        val result = collabMixer.edit(EditOrigin.USER, control, shown, block)
        if (result is CollabResult.Done) onDone(result.value)
    }

    private fun refuse(applied: Boolean) {
        if (!applied) local.update { it.copy(notice = VIBE_LOADING) } else clearNotice()
    }

    private fun sendNotice(result: SendEdit) {
        when (result) {
            SendEdit.APPLIED -> clearNotice()
            SendEdit.VIBE_LOADING -> local.update { it.copy(notice = VIBE_LOADING) }
            SendEdit.ZERO_SEND -> local.update { it.copy(notice = ZERO_SEND) }
        }
    }

    /** A landed edit answers only the loading notice; the zero-send one stays until dismissed or the scope changes. */
    private fun clearNotice() {
        local.update { if (it.notice == VIBE_LOADING) it.copy(notice = null) else it }
    }

    private fun capture() {
        if (local.value.capturing) return
        local.update { it.copy(capturing = true) }
        clearNotice()
        scope.launch(dispatchers.default) {
            val result = try {
                val o = collabMixer.capture()
                if (o == null) MixerCapture(true, MixCaptureTool.NOTHING_NEW, null, 0, null)
                else MixerCapture(o.ok, o.message, o.file, o.rows.size, o.stale)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "capture failed" }
                MixerCapture(false, e.message ?: "capture failed", null, 0, null)
            }
            local.update { it.copy(capture = result, capturing = false) }
        }
    }

    private data class Bench(
        val layers: BenchLayers,
        val scope: Scope,
        val muted: Set<Int>,
        val soloed: Set<Int>,
        val loop: Int?,
    )

    companion object {
        private const val METER_POLL_MS = 50L
        private const val MIN_DB = -30f
        private const val MAX_DB = 3.5f
        private const val VIBE_ROW_REFUSED =
            "That change can't be turned off on its own: roll back to a capture instead."
        private const val ENGINE_UNCHANGED = "The engine did not change. Try again once the vibe has loaded."
        private const val LOCK_UNCHANGED = "The lock did not change. Try again once the vibe has loaded."
        private const val ENGINES_VIBE_WIDE = "Engines change the whole vibe."
        private const val ZERO_SEND = "This send is 0 here, so a whole-vibe edit can't scale it; switch to section scope."

        fun previewFeature(
            state: AiMixerUiState = AiMixerUiState(),
            meters: MeterSnapshot,
            agentEdits: Flow<MixControl> = emptyFlow(),
        ): AiMixerFeature =
            object : AiMixerFeature {
                override val stateFlow: StateFlow<AiMixerUiState> = MutableStateFlow(state)
                override val actions: AiMixerActions = AiMixerActions.EMPTY
                override val meters: Flow<MeterSnapshot> = MutableStateFlow(meters)
                override val agentEdits: Flow<MixControl> = agentEdits
            }

        @Composable
        fun feature(): AiMixerFeature = synthFeature<AiMixerFeature>()
    }
}
