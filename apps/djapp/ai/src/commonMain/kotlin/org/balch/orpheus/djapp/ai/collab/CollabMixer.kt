package org.balch.orpheus.djapp.ai.collab

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.djapp.ai.mixer.f2
import org.balch.orpheus.djapp.ai.mixer.fixed
import org.balch.orpheus.djapp.ai.tools.trackStates
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SendEdit
import org.balch.orpheus.features.pulsar.bench.macroView
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable
import org.balch.orpheus.features.pulsar.bench.vibeDefault

/** How [CollabMixer.edit] went: the edit's own result, or the refusal that stopped it. */
sealed interface CollabResult<out T> {
    data class Done<T>(val value: T) : CollabResult<T>
    data class Refused(val refusal: Refusal, val reason: String) : CollabResult<Nothing>
}

/**
 * The one door to the mixer for the user, the in-app agent and MCP. Each edit is checked against [MixCollab],
 * run on the shared [MixBenchController], and recorded with its origin and the before and after as the mixer shows them.
 *
 * The controller and the feature are resolved lazily: this is an AppScope singleton and the feature lives in the
 * child graph, so resolving either in a constructor would close the DI cycle [org.balch.orpheus.djapp.ai.tools.MixReadTool] documents.
 */
@SingleIn(AppScope::class)
class CollabMixer internal constructor(
    controllerProvider: () -> MixBenchController,
    pulsarProvider: () -> PulsarFeature,
    private val collab: MixCollab,
    scope: CoroutineScope,
) {
    @Inject
    constructor(
        controllerProvider: () -> MixBenchController,
        pulsarProvider: () -> PulsarFeature,
        collab: MixCollab,
    ) : this(controllerProvider, pulsarProvider, collab, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val log = logging("CollabMixer")
    private val controller by lazy(controllerProvider)
    private val pulsar by lazy(pulsarProvider)

    /** Knob writes this class made, so the follower in [watchKnobsAndLoads] does not log them as the user's. */
    private data class OwnWrites(val inFlight: Map<Macro, Int> = emptyMap(), val expected: Map<Macro, Float> = emptyMap())

    private val own = MutableStateFlow(OwnWrites())

    /** The last vibe load count [collab] has been told about; -1 until first seen. Claimed by whichever of [edit] or the follower sees a load first. */
    private val handledLoad = MutableStateFlow(-1)

    init {
        scope.launch {
            try {
                watchKnobsAndLoads()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "knob follower failed" }
            }
        }
    }

    /**
     * Checks, runs [block] on the controller and records the move. [shown] is the text for "after" when the
     * mixer cannot show it yet (a section jump lands at the next bar line). An edit the controller did not apply
     * (it returned false, or a [SendEdit] other than APPLIED) is not recorded.
     */
    suspend fun <T> edit(
        origin: EditOrigin,
        control: MixControl,
        shown: String? = null,
        block: suspend MixBenchController.() -> T,
    ): CollabResult<T> {
        syncVibeLoad()
        collab.check(origin, control)?.let { return CollabResult.Refused(it, reasonFor(it)) }
        val c = controller
        // A reset moves every knob back to the file, so all four are this edit's own writes.
        val macros = when (control) {
            is MixControl.MacroKnob -> listOf(control.macro)
            MixControl.ResetToFile -> Macro.entries
            else -> emptyList()
        }
        val before = c.show(control)
        for (m in macros) own.update { it.copy(inFlight = it.inFlight + (m to ((it.inFlight[m] ?: 0) + 1))) }
        try {
            val value = c.block()
            if (value.wasApplied()) collab.record(origin, control, before, shown ?: c.show(control))
            return CollabResult.Done(value)
        } finally {
            for (m in macros) {
                val knob = pulsar.macroKnob(m)
                own.update {
                    it.copy(
                        inFlight = it.inFlight + (m to ((it.inFlight[m] ?: 1) - 1)),
                        expected = it.expected + (m to knob),
                    )
                }
            }
        }
    }

    /** [control]'s value now, as the mixer shows it. */
    suspend fun shown(control: MixControl): String = controller.show(control)

    /** What the user set [control] to this turn: a section jump's target, not the section still playing. */
    suspend fun userValue(control: MixControl): String = collab.userValueThisTurn(control) ?: shown(control)

    /** MCP when called under [TurnBudget.Exempt] (MCP has no agent turns), otherwise the in-app agent. */
    suspend fun toolOrigin(): EditOrigin =
        if (currentCoroutineContext()[TurnBudget.Exempt] != null) EditOrigin.MCP else EditOrigin.AGENT

    /**
     * Clears [collab] for a vibe load it has not been told about yet. Runs at the top of [edit], so an edit made right
     * after an apply is checked against the new vibe, not refused for the old one's pins or user moves. The compare-and-set
     * makes the clear happen once per load, whether [edit] or the follower in [watchKnobsAndLoads] gets there first.
     */
    private fun syncVibeLoad() {
        val count = pulsar.vibeLoadCount.value
        while (true) {
            val handled = handledLoad.value
            // Only forward: a caller holding a stale count must not undo a newer claim.
            if (handled >= count) return
            if (handledLoad.compareAndSet(handled, count)) {
                if (handled >= 0) collab.clearForNewVibe()
                return
            }
        }
    }

    /**
     * Logs a macro knob moved anywhere but through [edit] (Pulsar panel, MIDI, gestures) as a USER move, and
     * hands [MixCollab] each new vibe load. A load writes the new vibe's knobs just after it bumps the count,
     * so those writes are told apart from the user's by landing on the vibe's own values.
     */
    private suspend fun watchKnobsAndLoads() {
        val p = pulsar
        var load = p.vibeLoadCount.value
        syncVibeLoad()
        var seen = knobs(p)
        var loading = emptySet<Macro>()
        combine(p.stateFlow.map { knobs(p) }.distinctUntilChanged(), p.vibeLoadCount) { k, n -> k to n }
            .collect { (now, count) ->
                if (count != load) {
                    load = count
                    seen = now
                    loading = Macro.entries.toSet()
                    syncVibeLoad()
                    return@collect
                }
                for (m in Macro.entries) {
                    val v = now.getValue(m)
                    val old = seen.getValue(m)
                    if (v == old) continue
                    seen = seen + (m to v)
                    val writes = own.value
                    val busy = (writes.inFlight[m] ?: 0) > 0
                    val ours = busy || writes.expected[m] == v
                    if (!busy && m in writes.expected) own.update { it.copy(expected = it.expected - m) }
                    val fromLoad = m in loading && v == m.vibeDefault(p.vibeFlow.value)
                    loading = loading - m
                    if (!ours && !fromLoad) collab.record(EditOrigin.USER, MixControl.MacroKnob(m), f2(old), f2(v))
                }
            }
    }

    private fun knobs(p: PulsarFeature) = Macro.entries.associateWith(p::macroKnob)

    private suspend fun MixBenchController.trackState(t: Int) = trackStates(pulsar).getOrNull(t)

    /** The control's value as the mixer shows it. */
    private suspend fun MixBenchController.show(control: MixControl): String = when (control) {
        is MixControl.Level -> trackState(control.track)?.let { "${fixed(it.levelDb, 1)} dB" } ?: NA
        is MixControl.Pan -> trackState(control.track)?.let { panText(it.pan) } ?: NA
        is MixControl.Send -> trackState(control.track)?.let { f2(if (control.reverb) it.reverb else it.delay) } ?: NA
        is MixControl.Mute -> trackState(control.track)?.let { onOff(it.muted) } ?: NA
        is MixControl.Solo -> trackState(control.track)?.let { onOff(it.soloed) } ?: NA
        is MixControl.MacroKnob -> f2(macroValue(control.macro))
        MixControl.Section ->
            pulsar.vibeFlow.value.arrangement?.sections?.getOrNull(pulsar.arrangementStateFlow.value.sectionIndex)?.name ?: "none"
        MixControl.Loop -> onOff(loopSection.value != null)
        MixControl.EditScope -> if (scope.value == Scope.SECTION) "section" else "whole vibe"
        MixControl.ResetToFile -> {
            val vibe = pulsar.vibeFlow.value
            val knobsOff = Macro.entries.count { pulsar.macroKnob(it) != it.vibeDefault(vibe) }
            if (layerEditCount(layers.value) + muted.value.size + soloed.value.size + knobsOff == 0) "file" else "edited"
        }
    }

    /** The knob, or in section scope the value heard in the playing section, as the panel shows it. */
    private fun MixBenchController.macroValue(m: Macro): Float {
        val p = pulsar
        val vibe = p.vibeFlow.value
        val section = p.arrangementStateFlow.value.sectionIndex
        val knob = p.macroKnob(m)
        val inSection = scope.value == Scope.SECTION && vibe.sectionScopeAvailable() && section >= 0
        return if (inSection) layers.value.macroView(vibe, section, m, knob).heard else knob
    }

    companion object {
        private const val NA = "n/a"

        const val MIXER_CLOSED = "The Mixer is closed. Ask the user to open it; don't change the mix until they do."
        const val PINNED = "The user pinned this strip. Ask before changing it."
        const val USER_MOVED = "The user moved this control during your turn, so their value stands."
        const val WIPE_UNCONFIRMED = "This would clear the user's mix. Ask them first."

        fun reasonFor(refusal: Refusal): String = when (refusal) {
            Refusal.MIXER_CLOSED -> MIXER_CLOSED
            Refusal.PINNED -> PINNED
            Refusal.USER_MOVED -> USER_MOVED
            Refusal.WIPE_UNCONFIRMED -> WIPE_UNCONFIRMED
        }

        /** "L30", "C", "R25": percent off centre. */
        internal fun panText(pan: Float): String {
            val n = (abs(pan) * 100f).roundToInt()
            return if (n == 0) "C" else (if (pan < 0f) "L" else "R") + n
        }

        private fun onOff(on: Boolean) = if (on) "on" else "off"

        /** Values the layers hold over the vibe as written: 0 straight after a load or a reset. */
        private fun layerEditCount(layers: BenchLayers): Int {
            val v = layers.vibe
            val vibe = v.levelDb.count { it != 0f } + v.pan.count { it != null } +
                v.reverbRatio.count { it != 1f } + v.delayRatio.count { it != 1f } + v.macros.size
            return vibe + layers.sections.values.sumOf { s ->
                s.levelDb.count { it != 0f } + s.reverbSend.count { it != null } + s.delaySend.count { it != null } + s.macrosHeard.size
            }
        }

        private fun Any?.wasApplied() = this != false && this != SendEdit.VIBE_LOADING && this != SendEdit.ZERO_SEND
    }
}
