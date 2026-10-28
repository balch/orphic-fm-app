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
import kotlinx.serialization.json.JsonElement
import org.balch.orpheus.djapp.ai.mixer.f2
import org.balch.orpheus.djapp.ai.mixer.fixed
import org.balch.orpheus.djapp.ai.tools.trackStates
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.withEngineKnob
import org.balch.orpheus.features.pulsar.bench.withEngineKnobOf
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SendEdit
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.diff
import org.balch.orpheus.features.pulsar.bench.diffTree
import org.balch.orpheus.features.pulsar.bench.heardDelay
import org.balch.orpheus.features.pulsar.bench.macroView
import org.balch.orpheus.features.pulsar.bench.mixerChanges
import org.balch.orpheus.features.pulsar.bench.patched
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable
import org.balch.orpheus.features.pulsar.bench.structureRefusal
import org.balch.orpheus.features.pulsar.bench.toTree
import org.balch.orpheus.features.pulsar.bench.vibeDefault
import org.balch.orpheus.features.pulsar.models.Vibe

/** How [CollabMixer.edit] went: the edit's own result, or the refusal that stopped it. */
sealed interface CollabResult<out T> {
    data class Done<T>(val value: T) : CollabResult<T>
    data class Refused(val refusal: Refusal, val reason: String) : CollabResult<Nothing>
}

/** How [CollabMixer.editVibe] went: the fields it changed and the Mixer-owned ones it left alone, or why it refused. */
sealed interface VibeEditResult {
    /** [restarted] is false when only engine knobs changed, so the song played on. */
    data class Applied(val rows: Int, val ignoredMixerPaths: List<String>, val restarted: Boolean = true) : VibeEditResult
    data class Refused(val reason: String, val structure: Boolean = false) : VibeEditResult

    /** The edits could not be patched onto the vibe, or the result is not a valid vibe. */
    data class Invalid(val message: String) : VibeEditResult
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

    // Under [historyLock]. The vibe [applyOwn] is loading, or null. [syncVibeLoad] claims it without a new timeline while
    // it is the instance playing, whatever the count: a load that raced in just before it was overwritten by it.
    private var ownVibe: Vibe? = null

    // Under [historyLock]. The load whose restored vibe rows still wait to play over the file, or -1.
    private var restoreVibeFor = -1

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

    /**
     * Records [edited], the whole playing vibe as an edit wants it, as vibe rows on top of its file, then plays the
     * effective vibe, which restarts the song unless only engine knobs changed. Mixer-owned fields are left to the Mixer
     * and named in the result.
     * Refused, changing nothing, for another vibe's name or a change to the section or track structure.
     */
    suspend fun editVibe(origin: EditOrigin, edited: Vibe): VibeEditResult = editVibeWith(origin) { _, _ -> edited }

    /**
     * Like [editVibe], but patches [edits] (path to value) onto the effective vibe under the lock, so rows above a
     * heard point are kept. [VibeEditResult.Invalid] when a path or value does not fit the vibe.
     */
    suspend fun editVibe(origin: EditOrigin, edits: List<Pair<String, JsonElement>>): VibeEditResult =
        editVibeWith(origin) { _, effective -> effective.patched(edits) }

    /**
     * Turns [knob] on [track]'s [slot] engine to [value] and pins it, so the value plays over MOOD or SPACE; a null
     * [value] puts the knob and its pin back to the file. The rows land like [editVibe]'s, and the song plays on.
     */
    suspend fun editEngineKnob(origin: EditOrigin, track: Int, slot: EngineSlot, knob: EngineKnob, value: Float?): VibeEditResult =
        editVibeWith(origin) { file, effective ->
            if (value == null) effective.withEngineKnobOf(file, track, slot, knob) else effective.withEngineKnob(track, slot, knob, value)
        }

    private suspend fun editVibeWith(origin: EditOrigin, build: (file: Vibe, effective: Vibe) -> Vibe): VibeEditResult = historyLock.withLock {
        syncVibeLoad()
        val file = historyVibe ?: return@withLock VibeEditResult.Refused(NOT_PLAYING)
        val edited = try {
            build(file, vibeOrNull { _history.value.effective(file) } ?: file)
        } catch (e: Exception) {
            return@withLock VibeEditResult.Invalid(e.message ?: "The edit does not fit the vibe.")
        }
        if (!file.name.equals(edited.name, ignoreCase = true)) return@withLock VibeEditResult.Refused(NOT_PLAYING)
        structureRefusal(file, edited)?.let { return@withLock VibeEditResult.Refused(it, structure = true) }
        endHearLocked()
        if (origin == EditOrigin.AGENT) captureBeforeTurn()
        // Both of the above can wait; a vibe the user picked meanwhile wins, and this edit was for the one before.
        syncVibeLoad()
        if (historyVibe != file || loadPending()) return@withLock VibeEditResult.Refused(NOT_PLAYING)
        val old = _history.value
        // A name differing only in case is still this vibe; it must not become a row.
        val wanted = edited.copy(name = file.name)
        val current = vibeOrNull { old.effective(file) } ?: file
        val ignored = mixerChanges(current, wanted)
        val next: MixHistory
        val target: Vibe
        try {
            next = old.recordVibe(origin, file, wanted, nowMs())
            target = next.effective(file)
        } catch (e: Exception) {
            return@withLock VibeEditResult.Refused("The edit does not fit the vibe: ${e.message}")
        }
        if (target == current) return@withLock VibeEditResult.Applied(0, ignored)
        val loads = pulsar.vibeLoadCount.value
        // A load racing in from another thread refuses the play; then nothing is kept.
        if (!play(target, next.layers())) return@withLock VibeEditResult.Refused(NOT_PLAYING)
        commit(next)
        VibeEditResult.Applied(diffTree(current.toTree(), target.toTree()).size, ignored, pulsar.vibeLoadCount.value != loads)
    }

    /** The playing vibe as its file holds it, without the History's vibe rows; null before the first load. */
    suspend fun fileVibe(): Vibe? = historyLock.withLock {
        syncVibeLoad()
        historyVibe
    }

    /** The playing vibe with the History's vibe rows on, whatever a hear is previewing; null before the first load. */
    suspend fun effectiveVibe(): Vibe? = historyLock.withLock {
        syncVibeLoad()
        val file = historyVibe ?: return@withLock null
        vibeOrNull { _history.value.effective(file) } ?: file
    }

    /** Turns a change off or back on and plays the result. False, changing nothing, for an unknown id or during a vibe load. */
    suspend fun toggleChange(id: Int): Boolean = historyLock.withLock {
        if (loadPending()) return@withLock false
        val item = _history.value.items.firstOrNull { it.id == id && (it is HistoryChange || it is HistoryVibeEdit) }
            ?: return@withLock false
        endHearLocked()
        playHistory(_history.value.toggle(id), listOfNotNull((item as? HistoryChange)?.control))
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
        if (loadPending() || _history.value.uncapturedCount == 0) return null
        val file = fileVibeLocked()
        val outcome = captures.capture(file, _history.value.captureVibeRows(file))
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
        if (!play(fileVibeLocked(), BenchLayers())) return@withLock false
        replayFor = -1
        commit(_history.value.copy(items = emptyList(), pointCount = 0))
        true
    }

    /**
     * Plays the vibe and mix as they stood at the point ([MixHistory.FILE] for the file) without changing the history.
     * Different vibe rows there reload the vibe. False, changing nothing, when the point's rows no longer fit the file.
     */
    suspend fun hear(pointId: Int): Boolean = historyLock.withLock {
        if (loadPending() || !isPoint(pointId)) return@withLock false
        val h = _history.value
        val vibe = vibeOrNull { h.effectiveAt(fileVibeLocked(), pointId) } ?: return@withLock false
        val heard = play(vibe, h.layersAt(pointId))
        if (heard) _hearing.value = pointId
        heard
    }

    /** Goes back to playing the history after [hear]. */
    suspend fun endHear() {
        historyLock.withLock { endHearLocked() }
    }

    /** Drops every item after the point and plays what is left. False, changing nothing, for an unknown point or during a vibe load. */
    suspend fun rollBack(pointId: Int): Boolean = historyLock.withLock {
        if (loadPending() || !isPoint(pointId)) return@withLock false
        endHearLocked()
        val old = _history.value
        val next = old.rollBackTo(pointId)
        val dropped = old.changes.filter { it.on } - next.changes.toSet()
        playHistory(next, dropped.map { it.control }.distinct())
    }

    // Under [historyLock]. Records the layer fields the edit wrote; a reset keeps only the changes that survived it.
    private suspend fun recordHistory(
        origin: EditOrigin,
        control: MixControl,
        before: String,
        after: String,
        layersBefore: BenchLayers,
        layersAfter: BenchLayers,
    ) {
        if (control == MixControl.ClearAll) {
            val hadVibeRows = _history.value.vibeEdits.any { it.on }
            commit(_history.value.keepOnly(layersAfter, collab.pins.value))
            // A reset drops the vibe rows too, so the file plays again under the kept mix.
            if (hadVibeRows) play(fileVibeLocked(), _history.value.layers())
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
    // False, changing nothing, when its vibe rows no longer fit the file.
    private suspend fun playHistory(next: MixHistory, controls: List<MixControl>): Boolean {
        val c = controller
        val vibe = vibeOrNull { next.effective(fileVibeLocked()) } ?: return false
        val before = controls.map { c.show(it) }
        if (!play(vibe, next.layers())) return false
        commit(next)
        controls.forEachIndexed { i, control -> collab.record(EditOrigin.USER, control, before[i], c.show(control)) }
        return true
    }

    // Under [historyLock]. Back to the history's replay after a preview.
    private suspend fun endHearLocked() {
        // An unclaimed load must not get the old vibe's replay; claiming it ends the hear.
        syncVibeLoad()
        // A restored set plays before anything lands on top of it, so an edit's before is the restored vibe and mix.
        playRestoredVibe()
        replayRestored()
        if (_hearing.value == null) return
        val h = _history.value
        // False only during a load, which clears the layers and the history itself.
        play(vibeOrNull { h.effective(fileVibeLocked()) } ?: fileVibeLocked(), h.layers())
        _hearing.value = null
    }

    // Under [historyLock]. Plays [vibe] with [layers] over it. One that differs only in engine knobs retunes the song in
    // place; any other is loaded first as an own load (the song restarts), and is true from then on: a timed-out follow
    // leaves the mix to [replayRestored]. False, playing nothing, while a load is unclaimed: an own load must never
    // revert a vibe the user just picked.
    private suspend fun play(vibe: Vibe, layers: BenchLayers): Boolean {
        if (vibe == pulsar.vibeFlow.value) return applyLayers(layers)
        if (pulsar.vibeLoadCount.value == handledLoad.value && pulsar.retuneVibe(vibe)) return applyLayers(layers)
        if (!applyOwn(vibe)) return false
        if (controller.awaitLoadFollowed() && applyLayers(layers)) {
            replayFor = -1
        } else {
            log.warn { "'${vibe.name}' reloaded, but its mix edits did not reach the engine yet" }
            launchReplay()
        }
        return true
    }

    // Under [historyLock]. Loads [vibe] as this class's own load, keeping the timeline, the hear and the turn.
    // False, loading nothing, while another load is unclaimed.
    private fun applyOwn(vibe: Vibe): Boolean {
        if (pulsar.vibeLoadCount.value != handledLoad.value) return false
        ownVibe = vibe
        try {
            pulsar.applyVibe(vibe)
            syncVibeLoad()
        } finally {
            // An apply that threw loaded nothing, so no later load may pass for this one.
            ownVibe = null
        }
        return true
    }

    // Under [historyLock]. Plays the vibe rows a claim restored (or an outside re-apply kept) over the file, once.
    // A newer load or a hear plays its own; the effective vibe already playing only needs its mix replayed.
    private suspend fun playRestoredVibe() {
        val n = restoreVibeFor
        if (n < 0) return
        restoreVibeFor = -1
        if (n != handledLoad.value || n != pulsar.vibeLoadCount.value || _hearing.value != null) return
        val h = _history.value
        val vibe = vibeOrNull { h.effective(fileVibeLocked()) } ?: return
        if (vibe != pulsar.vibeFlow.value) {
            play(vibe, h.layers())
            return
        }
        replayFor = if (h.onCount > 0) n else -1
        replayRestored()
    }

    // Under [historyLock]. The vibe [history] belongs to, as its file holds it.
    private fun fileVibeLocked(): Vibe = historyVibe ?: pulsar.vibeFlow.value

    // Under [historyLock]. [h] with its vibe rows dropped (warned) when they no longer fit [file], so the file plays.
    private fun fitting(h: MixHistory, file: Vibe): MixHistory {
        if (h.vibeEdits.none { it.on }) return h
        vibeOrNull { h.effective(file) }?.let { return h }
        return h.copy(items = h.items.filterNot { it is HistoryVibeEdit })
    }

    // [block]'s vibe, or null (warned) when a vibe row no longer fits the file it patches.
    private fun vibeOrNull(block: () -> Vibe): Vibe? =
        try {
            block()
        } catch (e: Exception) {
            log.warn(e) { "vibe rows no longer fit the file" }
            null
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
     * An [applyOwn] load is claimed without any of that: it replays the same vibe with its vibe rows.
     */
    private fun syncVibeLoad(): Boolean {
        val count = pulsar.vibeLoadCount.value
        while (true) {
            val handled = handledLoad.value
            // Only forward: a caller holding a stale count must not undo a newer claim.
            if (handled >= count) return false
            if (handledLoad.compareAndSet(handled, count)) {
                if (ownVibe != null && pulsar.vibeFlow.value === ownVibe) {
                    // [applyOwn]'s reload of this vibe: same file, timeline, hear and turn. Its caller plays the mix.
                    ownVibe = null
                    replayFor = if (_history.value.onCount > 0) count else -1
                    return true
                }
                if (handled >= 0 && isReapplyOfEffective()) {
                    // The app re-applied the vibe it was playing (graph ready, engine rebuilt, Mode One): keep it all.
                    _hearing.value = null
                    restoreVibeFor = count
                    jobs.launch { historyLock.withLock { playRestoredVibe() } }
                    return true
                }
                if (handled >= 0) {
                    collab.clearForNewVibe()
                    // The turn's after-capture belonged to the vibe it replaced.
                    pendingTurn = null
                }
                val vibe = pulsar.vibeFlow.value
                val restored = store.get(vibe)?.let { fitting(it, vibe) }
                historyVibe = vibe
                _history.value = (restored ?: MixHistory()).copy(loadedAtMs = nowMs())
                _hearing.value = null
                // Restored vibe rows reload the file with them on top; that own load replays the mix after it.
                val ownVibe = restored != null && restored.vibeEdits.any { it.on }
                replayFor = if (!ownVibe && restored != null && restored.onCount > 0) count else -1
                restoreVibeFor = if (ownVibe) count else -1
                // Off the caller; the next locked call that ends a hear plays it first, which makes the job a no-op.
                if (ownVibe) jobs.launch { historyLock.withLock { playRestoredVibe() } }
                launchReplay()
                return true
            }
        }
    }

    // Under [historyLock]. True when the vibe now playing is the history's effective vibe (or the heard one), re-applied
    // from outside. Only with vibe rows: a reload of the bare file keeps today's fresh claim.
    private fun isReapplyOfEffective(): Boolean {
        val file = historyVibe ?: return false
        val h = _history.value
        val playing = pulsar.vibeFlow.value
        if (playing.name != file.name || h.vibeEdits.isEmpty() || playing == file) return false
        if (playing == vibeOrNull { h.effective(file) }) return true
        val heard = _hearing.value ?: return false
        return playing == vibeOrNull { h.effectiveAt(file, heard) }
    }

    // Under [historyLock]. Off the caller: the follower's collect must not wait out the load-follow timeout.
    private fun launchReplay() {
        if (replayFor < 0) return
        jobs.launch {
            controller.awaitLoadFollowed()
            historyLock.withLock { replayRestored() }
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
        const val NOT_PLAYING = "This is not the playing vibe. Edit the vibe that plays now, or apply a new one."

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
