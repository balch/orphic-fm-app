package org.balch.orpheus.features.dj

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.plugin.symbols.DjDrop
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.TimeSource

/** Two presses closer together than this count as a double press. */
internal const val DoublePressWindowMs = 400L

/** Peak platter velocity for a fling, in the same units as [faderVelocity] (±10). */
internal const val FlingVelocity = 7.5f

/** A single press nudges rather than flings, so one press is not a dead key. */
internal const val NudgeVelocity = 2.0f

/**
 * Picks a drop weighted by [DjDrop.weight], so BRAKE and FREEZE — which can go quiet or
 * stoppy — come up less often than the colourful ones. Reuses the same weights the zone
 * shuffle already tunes against rather than inventing a second distribution.
 */
internal fun weightedDrop(candidates: List<DjDrop>, random: Random): DjDrop {
    val pool = candidates.filter { it != DjDrop.NONE && it.weight > 0 }
    if (pool.isEmpty()) return DjDrop.NONE
    val total = pool.sumOf { it.weight }
    var ticket = random.nextInt(total)
    for (drop in pool) {
        ticket -= drop.weight
        if (ticket < 0) return drop
    }
    return pool.last()
}

/**
 * The auto-scratch: grab the platter, hold, drop in an effect, then work the platter back
 * and forth before releasing.
 *
 * Musical shape rather than pure noise. Steps land on beat subdivisions taken from
 * [beatMillis], the direction alternates so the motion reads as a scratch rather than a
 * drift, and the amplitude decays across the run so it resolves into the release instead of
 * being cut off mid-swipe. Length is a whole number of beats so the release lands on one.
 *
 * The caller must run this cancellably: the `finally` block is what guarantees a cancelled
 * run still releases the platter and clears the drop, rather than leaving the deck stuck
 * frozen with an effect latched on.
 */
internal suspend fun performRandomDrop(
    deck: Int,
    actions: DjPanelActions,
    zoneOrder: List<DjDrop>,
    beatMillis: Long,
    random: Random = Random.Default,
) {
    val beats = 2 + random.nextInt(3)              // 2..4 beats
    val stepsPerBeat = listOf(2, 4).random(random) // 1/8 or 1/16 feel
    val stepMs = (beatMillis / stepsPerBeat).coerceAtLeast(40L)
    val steps = beats * stepsPerBeat

    try {
        // Press and hold: taking the platter stops it dead, which is the setup for a drop.
        actions.setDragActive(deck, true)
        actions.setPlatterDrag(deck, 0f)
        delay(beatMillis / 2)

        actions.setDrop(deck, weightedDrop(zoneOrder, random))

        var direction = if (random.nextBoolean()) 1f else -1f
        for (step in 0 until steps) {
            // Decay to ~35% by the final step so the run resolves rather than stopping flat.
            val decay = 1f - 0.65f * (step.toFloat() / steps)
            val jitter = 0.75f + random.nextFloat() * 0.5f
            actions.setPlatterDrag(deck, direction * FlingVelocity * decay * jitter)
            delay(stepMs)
            direction = -direction
        }
    } finally {
        // Runs on cancellation too, so an interrupted drop cannot strand the deck.
        actions.setPlatterDrag(deck, 0f)
        actions.setPlatterRelease(deck)
        actions.setDragActive(deck, false)
        actions.setDrop(deck, DjDrop.NONE)
    }
}

/** Spin the platter and let it go: a flick rather than a sustained scratch. */
internal suspend fun performFling(deck: Int, actions: DjPanelActions, direction: Float) {
    try {
        actions.setDragActive(deck, true)
        actions.setPlatterDrag(deck, direction * FlingVelocity)
        delay(120)
    } finally {
        actions.setPlatterRelease(deck)
        actions.setDragActive(deck, false)
    }
}

/** The D-pad keys a platter reacts to, kept off Compose's `Key` so routing stays testable. */
internal enum class DeckKey { Up, Down, Select, Back, Other }

/** What a press means to the platter. */
internal sealed interface DeckAction {
    /** Not ours. Left unconsumed so Compose can move focus out of the deck. */
    data object Pass : DeckAction

    /** Take the platter, so the vertical axis starts driving it. */
    data object EnterAdjust : DeckAction

    /** Hand the platter back, so the vertical axis returns to moving focus. */
    data object ExitAdjust : DeckAction

    /** Spin the platter, [velocity] telling a nudge from a fling. */
    data class Spin(val direction: Float, val velocity: Float) : DeckAction

    data object RandomDrop : DeckAction
}

/**
 * Routes one D-pad press for a platter.
 *
 * The deck only owns its vertical axis *while grabbed*, which is the same select-activated
 * gate the knobs and faders use. Owning it unconditionally is what made the panel below a
 * deck unreachable: a consumed key ends Compose's focus search, so a remote could enter a
 * platter and never get back out downwards.
 *
 * Grabbing first also keeps the double press detectable — the pair of presses that flings is
 * read after the platter is already held, so neither of them has to double as focus movement.
 */
internal fun decideDeckKey(
    key: DeckKey,
    adjusting: Boolean,
    doubleVertical: Boolean,
    doubleSelect: Boolean,
): DeckAction = when (key) {
    DeckKey.Up, DeckKey.Down -> when {
        !adjusting -> DeckAction.Pass
        else -> DeckAction.Spin(
            direction = if (key == DeckKey.Up) 1f else -1f,
            velocity = if (doubleVertical) FlingVelocity else NudgeVelocity,
        )
    }

    DeckKey.Select -> when {
        adjusting && doubleSelect -> DeckAction.RandomDrop
        adjusting -> DeckAction.ExitAdjust
        else -> DeckAction.EnterAdjust
    }

    // Only swallowed while grabbed, so back still leaves the screen the rest of the time.
    DeckKey.Back -> if (adjusting) DeckAction.ExitAdjust else DeckAction.Pass

    DeckKey.Other -> DeckAction.Pass
}

/**
 * Makes a turntable reachable and playable from a D-pad.
 *
 * - select: grabs the platter, and a quick second press runs [performRandomDrop].
 * - up / down (once grabbed): one press nudges, two in quick succession fling that direction.
 * - back: hands the platter back.
 *
 * See [decideDeckKey] for why the vertical axis is gated behind the grab.
 */
@Composable
internal fun Modifier.deckDpad(
    deck: Int,
    actions: DjPanelActions,
    zoneOrder: List<DjDrop>,
    beatMillis: () -> Long,
    enabled: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    onAdjustingChanged: (Boolean) -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    val clock = remember { TimeSource.Monotonic }
    var lastVertical by remember { mutableStateOf<Pair<Key, TimeSource.Monotonic.ValueTimeMark>?>(null) }
    var lastSelect by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    var adjusting by remember { mutableStateOf(false) }

    fun recentlyPressed(mark: TimeSource.Monotonic.ValueTimeMark?): Boolean =
        mark != null && mark.elapsedNow().inWholeMilliseconds < DoublePressWindowMs

    fun setAdjusting(value: Boolean) {
        if (adjusting == value) return
        adjusting = value
        onAdjustingChanged(value)
    }

    return this
        .onFocusChanged {
            onFocusChanged(it.isFocused)
            // Losing focus must also drop the grab, or the platter would still be swallowing
            // the vertical axis the next time this deck is reached.
            if (!it.isFocused) setAdjusting(false)
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !enabled) return@onKeyEvent false

            val key = when (event.key) {
                Key.DirectionUp -> DeckKey.Up
                Key.DirectionDown -> DeckKey.Down
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> DeckKey.Select
                Key.Back, Key.Escape -> DeckKey.Back
                else -> DeckKey.Other
            }
            val isDoubleVertical = lastVertical
                ?.let { (k, mark) -> k == event.key && recentlyPressed(mark) } == true
            val decision = decideDeckKey(
                key = key,
                adjusting = adjusting,
                doubleVertical = isDoubleVertical,
                doubleSelect = recentlyPressed(lastSelect),
            )

            when (key) {
                DeckKey.Up, DeckKey.Down -> lastVertical = event.key to clock.markNow()
                DeckKey.Select -> lastSelect = clock.markNow()
                else -> Unit
            }

            when (decision) {
                DeckAction.Pass -> false

                DeckAction.EnterAdjust -> { setAdjusting(true); true }

                DeckAction.ExitAdjust -> { setAdjusting(false); true }

                is DeckAction.Spin -> {
                    if (running?.isActive != true) {
                        running = scope.launch {
                            performFling(
                                deck,
                                actions,
                                decision.direction * (decision.velocity / FlingVelocity),
                            )
                        }
                    }
                    true
                }

                DeckAction.RandomDrop -> {
                    if (running?.isActive != true) {
                        running = scope.launch {
                            performRandomDrop(deck, actions, zoneOrder, beatMillis())
                        }
                    }
                    true
                }
            }
        }
        .focusable(enabled = enabled)
}

/** True when [velocity] is within the platter's accepted range. */
internal fun isPlatterVelocitySane(velocity: Float): Boolean = abs(velocity) <= 10f
