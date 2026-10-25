package org.balch.orpheus.djapp.ai.collab

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.balch.orpheus.djapp.ai.mixer.f2
import org.balch.orpheus.djapp.ai.mixer.fixed
import org.balch.orpheus.djapp.ai.tools.trackStates
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SendEdit
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.diff
import org.balch.orpheus.features.pulsar.bench.heardDelay
import org.balch.orpheus.features.pulsar.bench.macroView
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable
import org.balch.orpheus.features.pulsar.bench.vibeDefault
import org.balch.orpheus.features.pulsar.models.Vibe

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
    private val jobs: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    changeStore: () -> MixChangeStore = { NoMixChangeStore() },
    captureService: () -> MixCaptureService = { UnavailableMixCaptureService() },
) {
    @Inject
    constructor(
        controllerProvider: () -> MixBenchController,
        pulsarProvider: () -> PulsarFeature,
        collab: MixCollab,
        changeStore: () -> MixChangeStore,
        captureService: () -> MixCaptureService,
    ) : this(
        controllerProvider, pulsarProvider, collab, CoroutineScope(SupervisorJob() + Dispatchers.Default),
        changeStore = changeStore, captureService = captureService,
    )

    private val log = logging("CollabMixer")
    private val controller by lazy(controllerProvider)
    private val pulsar by lazy(pulsarProvider)
    private val store by lazy(changeStore)
    private val captures by lazy(captureService)

    /** Knob writes this class made, so the follower in [watchKnobsAndLoads] does not log them as the user's. */
    private data class OwnWrites(val inFlight: Map<Macro, Int> = emptyMap(), val expected: Map<Macro, Float> = emptyMap())

    private val own = MutableStateFlow(OwnWrites())

    /** The last vibe load count [collab] has been told about; -1 until first seen. Claimed by whichever of [edit] or the follower sees a load first. */
    private val handledLoad = MutableStateFlow(-1)

    /**
     * Guards [history], [hearing] and every edit, so the controller's layers stay the history's replay. Not re-entrant:
     * a block passed to [edit] must not call back into a locked method ([toolOrigin], [shown] and [userValue] take no lock).
     */
    private val historyLock = Mutex()
    private val _history = MutableStateFlow(MixHistory())

    /** What an apply about to run will replace, kept for the load it causes ([forLoad]); see [keepBeforeApply]. */
    private data class Kept(val forLoad: Int, val before: BeforeApply)

    private val kept = MutableStateFlow<Kept?>(null)
    private val _hearing = MutableStateFlow<Int?>(null)

    // Under [historyLock]. The vibe [history] belongs to, as read at its claim; commits save under it.
    private var historyVibe: Vibe? = null

    // Under [historyLock]. The turn whose first agent edit passed the check, kept for its after-capture.
    private var pendingTurn: MixCollab.ClaimedTurn? = null

    // Under [historyLock]. Once per turn: captures the mix as the turn found it (a no-op when nothing is uncaptured).
    private suspend fun captureBeforeTurn() {
        val turn = collab.claimTurn() ?: return
        // A queued turn can begin before the collector sees the last one end; that turn's changes close first.
        pendingTurn?.let { captureLocked("after: " + cutLabel(it.label)) }
        pendingTurn = turn
        captureLocked("before: " + cutLabel(turn.label))
    }

    // Runs in this class's scope: DjAiChat ends the turn inside a synchronized block, which cannot wait on the lock.
    private suspend fun captureAfterTurns() {
        collab.endedTurnId.collect { ended ->
            try {
                historyLock.withLock {
                    val turn = pendingTurn?.takeIf { it.id <= ended } ?: return@withLock
                    pendingTurn = null
                    endHearLocked()
                    captureLocked("after: " + cutLabel(turn.label))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "after-turn capture failed" }
            }
        }
    }

    // Under [historyLock]. The load whose restored change set still waits to reach the controller, or -1.
    private var replayFor = -1

    /** Every layer edit made through [edit] since the vibe loaded. */
    val history: StateFlow<MixHistory> = _history.asStateFlow()

    /** The point being previewed with [hear], or null while the mix plays the history. */
    val hearing: StateFlow<Int?> = _hearing.asStateFlow()

    init {
        jobs.launch {
            try {
                watchKnobsAndLoads()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "knob follower failed" }
            }
        }
        jobs.launch {
            try {
                captureAfterTurns()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "turn capture follower failed" }
            }
        }
    }

    /**
     * Checks, runs [block] on the controller and records the move. [shown] is the text for "after" when the
     * mixer cannot show it yet (a section jump lands at the next bar line). An edit the controller did not apply
     * (it returned false, or a [SendEdit] other than APPLIED) is not recorded. An applied edit ends any [hear]
     * and lands in [history]; a reset keeps only the changes its layers still hold.
     */
    suspend fun <T> edit(
        origin: EditOrigin,
        control: MixControl,
        shown: String? = null,
        block: suspend MixBenchController.() -> T,
    ): CollabResult<T> = historyLock.withLock {
        syncVibeLoad()
        collab.check(origin, control)?.let { return@withLock CollabResult.Refused(it, reasonFor(it)) }
        val c = controller
        endHearLocked()
        if (origin == EditOrigin.AGENT) captureBeforeTurn()
        // A reset moves every knob back to the file, so all four are this edit's own writes.
        val macros = when (control) {
            is MixControl.MacroKnob -> listOf(control.macro)
            MixControl.ClearAll -> Macro.entries
            else -> emptyList()
        }
        val before = c.show(control)
        // A macro row's before is what turning the change off plays, which can differ from the knob.
        val rowBefore = if (control is MixControl.MacroKnob) f2(c.macroValue(control.macro, replayed = true)) else before
        var layersBefore = c.layers.value
        val value = ownKnobWrites(macros) { c.block() }
        if (value.wasApplied()) {
            val after = shown ?: c.show(control)
            collab.record(origin, control, before, after)
            // A load that landed during the block cleared the history; the controller then holds only what came after it.
            // Claiming it here also clears the move just recorded to [collab]: it belonged to the vibe before the load.
            val reloaded = syncVibeLoad()
            if (reloaded) layersBefore = BenchLayers()
            if (!reloaded || !c.vibeLoadInFlight()) recordHistory(origin, control, rowBefore, after, layersBefore, c.layers.value)
        }
        CollabResult.Done(value)
    }

    /** Turns a change off or back on and plays the result. False, changing nothing, for an unknown id or during a vibe load. */
    suspend fun toggleChange(id: Int): Boolean = historyLock.withLock {
        if (loadPending()) return@withLock false
        val change = _history.value.changes.firstOrNull { it.id == id } ?: return@withLock false
        endHearLocked()
        playHistory(_history.value.toggle(id), listOf(change.control))
    }

    /** Marks a point in the timeline; a null [label] numbers it. */
    suspend fun markPoint(label: String? = null) {
        historyLock.withLock {
            if (loadPending()) return@withLock
            endHearLocked()
            commit(_history.value.mark(nowMs(), label))
        }
    }

    /**
     * Ends any hear, then saves the uncaptured changes as a capture file and adds a CAPTURE point that names it. Null,
     * writing nothing, when there are none. A failed capture adds no point. Holds the lock, so the file matches the history.
     */
    suspend fun capture(label: String = CAPTURED): CaptureOutcome? = historyLock.withLock {
        endHearLocked()
        captureLocked(label)
    }

    // Under [historyLock], after [endHearLocked]; the service never calls back into this class.
    private suspend fun captureLocked(label: String): CaptureOutcome? {
        if (loadPending() || _history.value.uncaptured.isEmpty()) return null
        val outcome = captures.capture()
        // A load during the write took the history; the point would belong to the vibe before it.
        if (outcome.ok && !syncVibeLoad()) commit(_history.value.capture(nowMs(), label, outcome.file))
        return outcome
    }

    /**
     * Empties a vibe's saved change set, pins or not. The playing vibe (null or its name) also ends any hear and plays
     * the file; another vibe just loses its stored set. False, changing nothing, during a vibe load.
     */
    suspend fun clearChanges(vibeName: String? = null): Boolean = historyLock.withLock {
        if (loadPending()) return@withLock false
        if (vibeName != null && !vibeName.equals(pulsar.vibeFlow.value.name, ignoreCase = true)) {
            // The store keys by exact name; an AI vibe is not in vibeNames, so its given name stands.
            store.remove(pulsar.vibeNames.firstOrNull { it.equals(vibeName, ignoreCase = true) } ?: vibeName)
            return@withLock true
        }
        endHearLocked()
        if (!applyLayers(BenchLayers())) return@withLock false
        replayFor = -1
        commit(_history.value.copy(items = emptyList(), pointCount = 0))
        true
    }

    /** Plays the mix as it stood at the point ([MixHistory.FILE] for the file) without changing the history. */
    suspend fun hear(pointId: Int): Boolean = historyLock.withLock {
        if (loadPending() || !isPoint(pointId)) return@withLock false
        val heard = applyLayers(_history.value.layersAt(pointId))
        if (heard) _hearing.value = pointId
        heard
    }

    /** Goes back to playing the history after [hear]. */
    suspend fun endHear() {
        historyLock.withLock { endHearLocked() }
    }

    /**
     * Keeps the vibe playing now and its timeline for the next load, which an [origin] apply is about to cause, so the
     * new vibe's timeline can roll back to them ([MixHistory.BEFORE_APPLY]). [dropBeforeApply] when the apply fails.
     */
    suspend fun keepBeforeApply(origin: EditOrigin) = historyLock.withLock {
        endHearLocked()
        val p = pulsar
        kept.value = Kept(p.vibeLoadCount.value + 1, BeforeApply(p.vibeFlow.value, _history.value, origin, nowMs()))
    }

    fun dropBeforeApply() {
        kept.value = null
    }

    /**
     * Drops every item after the point and plays what is left. [MixHistory.BEFORE_APPLY] instead loads the vibe the
     * apply replaced and brings back its timeline. False, changing nothing, for an unknown point or during a vibe load.
     */
    suspend fun rollBack(pointId: Int): Boolean = historyLock.withLock {
        if (pointId == MixHistory.BEFORE_APPLY) return@withLock restoreBeforeApply()
        if (loadPending() || !isPoint(pointId)) return@withLock false
        endHearLocked()
        val old = _history.value
        val next = old.rollBackTo(pointId)
        val dropped = old.changes.filter { it.on } - next.changes.toSet()
        playHistory(next, dropped.map { it.control }.distinct())
    }

    // Under [historyLock]. The load claims a fresh timeline, which the replaced vibe's then overwrites. Its layers wait
    // for the controller to follow the load: before that it refuses them as edits for the vibe being replaced.
    private suspend fun restoreBeforeApply(): Boolean {
        if (loadPending()) return false
        val before = _history.value.beforeApply ?: return false
        endHearLocked()
        kept.value = null
        pulsar.applyVibe(before.vibe)
        syncVibeLoad()
        commit(before.history)
        // The replay below covers what the claim restored.
        replayFor = -1
        val c = controller
        if (!c.awaitLoadFollowed() || !applyLayers(before.history.layers())) {
            log.warn { "rollback to before the apply: '${before.vibe.name}' loaded, but its mix edits did not reach the engine" }
        }
        return true
    }

    // Under [historyLock]. Records the layer fields the edit wrote; a reset keeps only the changes that survived it.
    private fun recordHistory(
        origin: EditOrigin,
        control: MixControl,
        before: String,
        after: String,
        layersBefore: BenchLayers,
        layersAfter: BenchLayers,
    ) {
        if (control == MixControl.ClearAll) {
            commit(_history.value.keepOnly(layersAfter, collab.pins.value))
            return
        }
        val writes = layersBefore.diff(layersAfter)
        val section = writes.keys.firstNotNullOfOrNull(LayerField::section)
        val name = section?.let { pulsar.vibeFlow.value.arrangement?.sections?.getOrNull(it)?.name }
        val h = _history.value
        commit(h.record(origin, control, section, name, before, after, writes, nowMs()))
    }

    // Under [historyLock]. Every History change but a load's seed goes through here, so the store keeps each vibe's set.
    private fun commit(next: MixHistory) {
        if (next == _history.value) return
        _history.value = next
        historyVibe?.let { store.put(it, next) }
    }

    // Under [historyLock]. Plays the change set a claim restored once the controller follows that load. A newer load
    // or a hear drops it (each replays on its own); a timed-out wait leaves it for the next caller.
    private suspend fun replayRestored() {
        val n = replayFor
        if (n < 0) return
        if (n != handledLoad.value || n != pulsar.vibeLoadCount.value || _hearing.value != null) {
            replayFor = -1
            return
        }
        if (!controller.awaitLoadFollowed()) {
            log.warn { "restored mix edits wait on a vibe load the mixer has not followed" }
            return
        }
        if (pulsar.vibeLoadCount.value != n || applyLayers(_history.value.layers())) replayFor = -1
    }

    // Under [historyLock]. Plays [next] and keeps it only if the controller took it, then tells the feed what moved.
    private suspend fun playHistory(next: MixHistory, controls: List<MixControl>): Boolean {
        val c = controller
        val before = controls.map { c.show(it) }
        if (!applyLayers(next.layers())) return false
        commit(next)
        controls.forEachIndexed { i, control -> collab.record(EditOrigin.USER, control, before[i], c.show(control)) }
        return true
    }

    // Under [historyLock]. Back to the history's replay after a preview.
    private suspend fun endHearLocked() {
        // An unclaimed load must not get the old vibe's replay; claiming it ends the hear.
        syncVibeLoad()
        // A restored set plays before anything lands on top of it, so an edit's before is the restored mix.
        replayRestored()
        if (_hearing.value == null) return
        // False only during a load, which clears the layers and the history itself.
        applyLayers(_history.value.layers())
        _hearing.value = null
    }

    // Whole-vibe macros the layers move are knob writes, so the follower does not log them as the user's.
    private suspend fun applyLayers(layers: BenchLayers): Boolean {
        val c = controller
        val macros = c.layers.value.diff(layers).keys.filterIsInstance<LayerField.VibeMacro>().map { it.macro }
        return ownKnobWrites(macros) { c.applyLayers(layers) }
    }

    private suspend fun <T> ownKnobWrites(macros: List<Macro>, block: suspend () -> T): T {
        for (m in macros) own.update { it.copy(inFlight = it.inFlight + (m to ((it.inFlight[m] ?: 0) + 1))) }
        try {
            return block()
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

    private fun isPoint(id: Int) = id == MixHistory.FILE || _history.value.items.any { it is HistoryPoint && it.id == id }

    // Under [historyLock]. True while a vibe load is unclaimed, so the history still belongs to the vibe before it.
    private fun loadPending(): Boolean {
        // Nothing is recorded before the first claim, so that one is safe to take here.
        if (handledLoad.value < 0) syncVibeLoad()
        return pulsar.vibeLoadCount.value > handledLoad.value
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
     * Under [historyLock]: a claimed load also swaps [history] for the vibe's stored change set (or an empty one) and
     * ends [hear]; a restored set is replayed by [replayRestored] once the controller follows. True when this call claimed one.
     */
    private fun syncVibeLoad(): Boolean {
        val count = pulsar.vibeLoadCount.value
        while (true) {
            val handled = handledLoad.value
            // Only forward: a caller holding a stale count must not undo a newer claim.
            if (handled >= count) return false
            if (handledLoad.compareAndSet(handled, count)) {
                if (handled >= 0) {
                    collab.clearForNewVibe()
                    // The turn's after-capture belonged to the vibe it replaced.
                    pendingTurn = null
                }
                // At or past its load (other loads may have landed unclaimed since), kept goes into this timeline.
                val k = kept.value?.takeIf { count >= it.forLoad }
                if (k != null) kept.value = null
                val vibe = pulsar.vibeFlow.value
                val restored = store.get(vibe)
                historyVibe = vibe
                _history.value = (restored ?: MixHistory()).copy(loadedAtMs = nowMs(), beforeApply = k?.before)
                _hearing.value = null
                replayFor = if (restored != null && restored.onCount > 0) count else -1
                // Off the caller: the follower's collect must not wait out the load-follow timeout.
                if (replayFor >= 0) {
                    jobs.launch {
                        controller.awaitLoadFollowed()
                        historyLock.withLock { replayRestored() }
                    }
                }
                return true
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
        historyLock.withLock { syncVibeLoad() }
        var seen = knobs(p)
        var loading = emptySet<Macro>()
        combine(p.stateFlow.map { knobs(p) }.distinctUntilChanged(), p.vibeLoadCount) { k, n -> k to n }
            .collect { (now, count) ->
                if (count != load) {
                    load = count
                    seen = now
                    loading = Macro.entries.toSet()
                    historyLock.withLock { syncVibeLoad() }
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
        is MixControl.DelaySetting -> pulsar.vibeFlow.value.let { vibe ->
            f2(layers.value.heardDelay(vibe, pulsar.arrangementStateFlow.value.sectionIndex, control.param))
        }
        MixControl.Section ->
            pulsar.vibeFlow.value.arrangement?.sections?.getOrNull(pulsar.arrangementStateFlow.value.sectionIndex)?.name ?: "none"
        MixControl.Loop -> onOff(loopSection.value != null)
        MixControl.EditScope -> if (scope.value == Scope.SECTION) "section" else "whole vibe"
        MixControl.ClearAll -> {
            val vibe = pulsar.vibeFlow.value
            val knobsOff = Macro.entries.count { pulsar.macroKnob(it) != it.vibeDefault(vibe) }
            if (layerEditCount(layers.value) + muted.value.size + soloed.value.size + knobsOff == 0) "file" else "edited"
        }
    }

    /**
     * The knob, or in section scope the value heard in the playing section, as the panel shows it. [replayed] gives
     * the whole-vibe value the layers replay to instead: a macro they never set plays the vibe's value, not the knob.
     */
    private fun MixBenchController.macroValue(m: Macro, replayed: Boolean = false): Float {
        val p = pulsar
        val vibe = p.vibeFlow.value
        val section = p.arrangementStateFlow.value.sectionIndex
        val knob = p.macroKnob(m)
        val inSection = scope.value == Scope.SECTION && vibe.sectionScopeAvailable() && section >= 0
        return when {
            // An unset section macro plays the knob, which replay leaves alone, so the shown value is the replayed one.
            inSection -> layers.value.macroView(vibe, section, m, knob).heard
            replayed -> layers.value.vibe.macros[m] ?: m.vibeDefault(vibe)
            else -> knob
        }
    }

    companion object {
        private const val NA = "n/a"

        /** The label of the History point a capture adds. */
        const val CAPTURED = "Captured"

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
                v.reverbRatio.count { it != 1f } + v.delayRatio.count { it != 1f } + v.macros.size + v.delayFx.size
            return vibe + layers.sections.values.sumOf { s ->
                s.levelDb.count { it != 0f } + s.reverbSend.count { it != null } + s.delaySend.count { it != null } +
                    s.macrosHeard.size + s.delayFx.size
            }
        }

        private fun Any?.wasApplied() = this != false && this != SendEdit.VIBE_LOADING && this != SendEdit.ZERO_SEND
    }
}

private const val POINT_LABEL_MAX = 40

/** One line of at most [POINT_LABEL_MAX] characters, cut at a word with "…" when it was longer. */
internal fun cutLabel(text: String): String {
    val line = text.replace(Regex("\\s+"), " ").trim()
    if (line.length <= POINT_LABEL_MAX) return line
    val room = line.take(POINT_LABEL_MAX - 1)
    val cut = room.lastIndexOf(' ').takeIf { it > 0 } ?: room.length
    return room.take(cut).trimEnd() + "…"
}
