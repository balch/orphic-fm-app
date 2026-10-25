package org.balch.orpheus.djapp.ai.collab

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** One recorded change to a mixer control, with the values as the mixer shows them. */
data class MixMove(
    val origin: EditOrigin,
    val control: MixControl,
    val before: String,
    val after: String,
    val atMs: Long,
)

/** Why [MixCollab.check] turned an edit down. */
enum class Refusal { MIXER_CLOSED, PINNED, USER_MOVED, WIPE_UNCONFIRMED }

/**
 * Shared state for the user, the in-app agent and MCP working the same mixer: pins, the agent's
 * turn, and the log of who moved what. Callers arrive from several coroutines, so every value is
 * immutable and updated through [update].
 */
@SingleIn(AppScope::class)
class MixCollab internal constructor(private val nowMs: () -> Long) {
    @Inject
    constructor() : this({ Clock.System.now().toEpochMilliseconds() })

    private val _mixerVisible = MutableStateFlow(false)
    val mixerVisible: StateFlow<Boolean> = _mixerVisible.asStateFlow()

    private val _pins = MutableStateFlow<Set<Int>>(emptySet())
    val pins: StateFlow<Set<Int>> = _pins.asStateFlow()

    private val _turnActive = MutableStateFlow(false)
    val turnActive: StateFlow<Boolean> = _turnActive.asStateFlow()

    private val _agentTouched = MutableStateFlow<Set<Int>>(emptySet())
    val agentTouched: StateFlow<Set<Int>> = _agentTouched.asStateFlow()

    private val _lastUserMoveAtMs = MutableStateFlow<Long?>(null)
    val lastUserMoveAtMs: StateFlow<Long?> = _lastUserMoveAtMs.asStateFlow()

    private val _moves = MutableSharedFlow<MixMove>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val moves: SharedFlow<MixMove> = _moves.asSharedFlow()

    private val _agentEdits = MutableSharedFlow<MixControl>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val agentEdits: SharedFlow<MixControl> = _agentEdits.asSharedFlow()

    /** Controls the user moved since [beginTurn]; the agent leaves those alone. */
    private val userMovedThisTurn = MutableStateFlow<Set<MixControl>>(emptySet())

    /** USER and MCP moves the agent has not been told about yet, one entry per (origin, control). */
    private val pendingLog = MutableStateFlow<List<MixMove>>(emptyList())

    private val wipeConfirmed = MutableStateFlow(false)

    private val _userChanged = MutableStateFlow<Set<MixControl>>(emptySet())

    /** Controls the user changed since the last vibe load or reset: what a wipe would take from them. */
    val userChanged: StateFlow<Set<MixControl>> = _userChanged.asStateFlow()

    fun setMixerVisible(visible: Boolean) {
        _mixerVisible.value = visible
    }

    fun togglePin(track: Int) {
        _pins.update { if (track in it) it - track else it + track }
    }

    /** The turn's label and whether its History point was taken, held together so the claim is one atomic step. */
    private data class TurnPoint(val label: String? = null, val used: Boolean = false)

    private val turnPoint = MutableStateFlow(TurnPoint())

    /** What the turn is about, for the History point before its first move; null outside a turn. */
    val turnLabel: String? get() = turnPoint.value.label

    /** The turn's label the first time it is asked, null after that: one History point per turn. */
    fun claimTurnLabel(): String? {
        val was = turnPoint.getAndUpdate { it.copy(used = true) }
        return was.label.takeUnless { was.used }
    }

    /** A confirmation from an earlier turn never authorises a wipe in this one. */
    fun beginTurn(label: String? = null) {
        turnPoint.value = TurnPoint(label)
        userMovedThisTurn.value = emptySet()
        wipeConfirmed.value = false
        _turnActive.value = true
    }

    /** A confirmation the turn never spent goes with it. */
    fun endTurn() {
        turnPoint.value = TurnPoint()
        _turnActive.value = false
        userMovedThisTurn.value = emptySet()
        _agentTouched.value = emptySet()
        wipeConfirmed.value = false
    }

    /** Null when the edit may go ahead. */
    fun check(origin: EditOrigin, control: MixControl): Refusal? {
        if (origin == EditOrigin.USER) return null
        if (origin == EditOrigin.AGENT && !_mixerVisible.value) return Refusal.MIXER_CLOSED
        if (control.track?.let { it in _pins.value } == true) return Refusal.PINNED
        if (origin == EditOrigin.AGENT && _turnActive.value && control in userMovedThisTurn.value) return Refusal.USER_MOVED
        return null
    }

    fun record(origin: EditOrigin, control: MixControl, before: String, after: String) {
        val move = MixMove(origin, control, before, after, nowMs())
        trackUserChanges(origin, control, before != after)
        when (origin) {
            EditOrigin.USER -> {
                _lastUserMoveAtMs.value = move.atMs
                if (_turnActive.value) userMovedThisTurn.update { it + control }
                log(move)
            }
            EditOrigin.AGENT -> {
                control.track?.let { track -> _agentTouched.update { it + track } }
                _agentEdits.tryEmit(control)
            }
            EditOrigin.MCP -> {
                log(move)
                _agentEdits.tryEmit(control)
            }
        }
        _moves.tryEmit(move)
    }

    /** Where the user left [control] since the turn began, as the mixer showed it; null when they did not move it. */
    fun userValueThisTurn(control: MixControl): String? =
        if (control in userMovedThisTurn.value) pendingLog.value.lastOrNull { it.origin == EditOrigin.USER && it.control == control }?.after else null

    /** USER and MCP moves since the last call, oldest first; a move that ended where it began is left out. */
    fun takeMovesForAgent(): List<MixMove> =
        pendingLog.getAndUpdate { emptyList() }.filter { it.before != it.after }

    fun confirmWipe() {
        wipeConfirmed.value = true
    }

    /** True once per [confirmWipe]. */
    fun consumeWipeConfirmation(): Boolean = wipeConfirmed.getAndUpdate { false }

    /** The user's changes a `mix_reset` would clear: the mix of unpinned strips and the macro knobs. */
    fun userChangesClearedByReset(): Int {
        val pins = _pins.value
        return _userChanged.value.count { it.clearedByReset() && it.track !in pins }
    }

    /** The user's changes an apply or a vibe load would clear: the mix itself, not where they are in the song. */
    fun userChangesWipedByLoad(): Int = _userChanged.value.count { it.wipedByLoad() }

    /** A new vibe replaces the tracks, so pins, the glow, this turn's user moves, the pending log and a wipe confirmation go. */
    fun clearForNewVibe() {
        turnPoint.value = TurnPoint()
        _pins.value = emptySet()
        userMovedThisTurn.value = emptySet()
        _agentTouched.value = emptySet()
        pendingLog.value = emptyList()
        wipeConfirmed.value = false
        _userChanged.value = emptySet()
    }

    /**
     * A reset clears the user's changes it reset, leaving pinned strips' own; a user reset also spends any
     * confirmation. Agent and MCP edits are never the user's.
     */
    private fun trackUserChanges(origin: EditOrigin, control: MixControl, changed: Boolean) {
        when {
            control == MixControl.ResetToFile -> {
                val pins = _pins.value
                _userChanged.update { set -> set.filterNot { it.clearedByReset() && it.track !in pins }.toSet() }
                if (origin == EditOrigin.USER) wipeConfirmed.value = false
            }
            origin == EditOrigin.USER && changed -> _userChanged.update { it + control }
        }
    }

    // A section jump, the loop and the edit scope lose nothing the user would miss.
    private fun MixControl.wipedByLoad() =
        this is MixControl.Level || this is MixControl.Pan || this is MixControl.Send ||
            this is MixControl.Mute || this is MixControl.Solo || this is MixControl.MacroKnob ||
            this is MixControl.DelaySetting

    // A reset clears what a load does; pinned strips are left out by the callers.
    private fun MixControl.clearedByReset() = wipedByLoad()

    /** Folds a move into the pending entry for its (origin, control): first before, last after and time. */
    private fun log(move: MixMove) {
        pendingLog.update { log ->
            val i = log.indexOfFirst { it.origin == move.origin && it.control == move.control }
            if (i < 0) {
                log + move
            } else {
                log.toMutableList().also { it[i] = log[i].copy(after = move.after, atMs = move.atMs) }
            }
        }
    }
}
