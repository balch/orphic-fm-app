package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** One arrow press moves the strike point this far, in the bell's 0..1 box. */
internal const val KEY_STEP = 0.05f

/** Presses closer together than this strike at full velocity. */
private const val FAST_GAP_MS = 120L

/** Presses farther apart than this (and the first press) strike at [SLOW_VELOCITY]. */
private const val SLOW_GAP_MS = 600L

private const val SLOW_VELOCITY = 0.45f

/** A SYNTH hold has no strike velocity; its ripple uses a middling one, as a pointer hold does. */
private const val HOLD_RIPPLE_VELOCITY = 0.6f

private val keyClock = TimeSource.Monotonic.markNow()

/** Milliseconds on a monotonic clock, from first use. */
internal fun keyClockMs(): Long = keyClock.elapsedNow().inWholeMilliseconds

/**
 * Where the remote strikes the bell: a point in the same 0..1 box the pointer path sends (x is
 * POSITION, y is STRUCTURE and runs downward). Also holds what the keys need between events.
 */
internal class BellKeyCursor(start: Offset = Offset(0.5f, 0.5f)) {
    /** Read while drawing, so a nudge redraws the ring and recomposes nothing. */
    var point by mutableStateOf(start)
        private set

    /** True while the bell has focus; the strike-point ring shows then. */
    var focused by mutableStateOf(false)
        internal set

    internal var okDown = false
    internal var holding = false
    private var lastPressMs = Long.MIN_VALUE

    /** Moves by [dx], [dy] steps (y grows downward). False, moving nothing, when already at that edge. */
    fun nudge(dx: Int, dy: Int): Boolean {
        val moved = Offset(snap(point.x + dx * KEY_STEP), snap(point.y + dy * KEY_STEP))
        if (moved == point) return false
        point = moved
        return true
    }

    /** Strike velocity from the gap since the previous press: quick presses are loud. */
    fun velocityFor(pressAtMs: Long): Float {
        val gap = if (lastPressMs == Long.MIN_VALUE) Long.MAX_VALUE else pressAtMs - lastPressMs
        lastPressMs = pressAtMs
        return when {
            gap <= FAST_GAP_MS -> 1f
            gap >= SLOW_GAP_MS -> SLOW_VELOCITY
            else -> 1f - (1f - SLOW_VELOCITY) * (gap - FAST_GAP_MS) / (SLOW_GAP_MS - FAST_GAP_MS)
        }
    }

    // Rounded so repeated 0.05 steps land exactly on the edge instead of just short of it.
    private fun snap(v: Float) = ((v * 10_000f).roundToInt() / 10_000f).coerceIn(0f, 1f)
}

/**
 * Plays the bell from a remote or keyboard once it has focus. OK strikes (TAP, SHAKE) or holds
 * (SYNTH); arrows move the strike point, sweeping a held note. An arrow at the edge is left
 * unhandled so focus can leave, unless a note is held. [onStruck] gets the 0..1 point and
 * velocity, for the ripple.
 */
internal fun Modifier.cowbellBellKeys(
    mode: CowbellMode,
    actions: CowbellPanelActions,
    cursor: BellKeyCursor,
    onStruck: (point: Offset, velocity: Float) -> Unit = { _, _ -> },
    nowMs: () -> Long = ::keyClockMs,
): Modifier = this
    // Before focusable, so the handler is in the chain of the node that takes focus.
    .onKeyEvent { cursor.handle(it, mode, actions, onStruck, nowMs) }
    .onFocusChanged {
        cursor.focused = it.isFocused
        if (!it.isFocused) cursor.letGo(actions)
    }
    .focusable()

private fun BellKeyCursor.handle(
    event: KeyEvent,
    mode: CowbellMode,
    actions: CowbellPanelActions,
    onStruck: (Offset, Float) -> Unit,
    nowMs: () -> Long,
): Boolean {
    val step = event.arrowStep()
    if (step != null) {
        if (event.type != KeyEventType.KeyDown) return false
        // At the edge a held note swallows the arrow, so a sweep can never skip the song; with
        // nothing held it is left unhandled and focus can leave.
        if (!nudge(step.first, step.second)) return holding
        if (holding) actions.onMove(point.x, point.y)
        return true
    }
    if (!event.isOk()) return false
    when (event.type) {
        KeyEventType.KeyDown -> {
            // Auto-repeat is a KeyDown with no KeyUp between: velocity comes from how fast the user presses.
            if (okDown) return true
            okDown = true
            if (mode == CowbellMode.SYNTH) {
                holding = true
                actions.onHold(point.x, point.y)
                onStruck(point, HOLD_RIPPLE_VELOCITY)
            } else {
                // The remote has no motion, so SHAKE strikes too.
                val velocity = velocityFor(nowMs())
                actions.onStrike(velocity, point.x, point.y)
                onStruck(point, velocity)
            }
        }
        KeyEventType.KeyUp -> letGo(actions)
        else -> return false
    }
    return true
}

/** Ends a held press and any held note. Safe with nothing held. */
internal fun BellKeyCursor.letGo(actions: CowbellPanelActions) {
    okDown = false
    if (holding) {
        holding = false
        actions.onRelease()
    }
}

/** Left and right nudge x; up raises STRUCTURE, which is a smaller y. */
private fun KeyEvent.arrowStep(): Pair<Int, Int>? = when (key) {
    Key.DirectionLeft -> -1 to 0
    Key.DirectionRight -> 1 to 0
    Key.DirectionUp -> 0 to -1
    Key.DirectionDown -> 0 to 1
    else -> null
}

private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar
