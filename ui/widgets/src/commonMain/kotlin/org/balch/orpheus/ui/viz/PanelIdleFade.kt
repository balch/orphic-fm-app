package org.balch.orpheus.ui.viz

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** Quiet time before the content panels start to go. Hovering does not break it. */
const val PanelIdleTimeoutMs = 3_000L

/** How long they take to go, once the quiet time has run out. */
const val PanelFadeOutMs = 600

/**
 * How fast a click, drag, scroll or key brings them back. Short: a tap must feel answered, and it
 * is also how long the wake overlay can eat the second half of a fast double click.
 */
const val PanelFadeInMs = 150

/**
 * How often a held drag may tear down and rebuild the watcher's coroutine while the panels are
 * already fully up. A drag reports movement continuously and every report still counts as
 * activity, since [PanelIdleFade.notifyActivity] always records the time, so this costs nothing in
 * correctness: the watcher waits out whatever quiet time is actually left, not a fixed delay.
 */
const val PanelActivityThrottleMs = 250L

/**
 * The content panels' idle fade, modelled on
 * [org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder]: [alpha] is an [Animatable] read
 * only from the draw phase (see [panelIdleFade]) and [activityTick] is what a watcher keys a
 * `LaunchedEffect` on, so neither the fade nor a burst of input ever recomposes a panel.
 *
 * [elapsedMs] is a seam for tests, which drive a virtual clock rather than a real one.
 */
@Stable
class PanelIdleFade(private val elapsedMs: () -> Long = monotonicMs()) {

    /** 1f = fully shown, 0f = faded out after [PanelIdleTimeoutMs] of quiet. */
    val alpha = Animatable(1f)

    /** Bumped when input should restart the watcher's coroutine; it keys a `LaunchedEffect`. */
    var activityTick: Int by mutableIntStateOf(0)
        private set

    /**
     * How many modal layers (sheets, dropdown popups) are open. They render in their own windows
     * and would not fade with the panels' alpha, so while any is open the panels stay up and the
     * countdown does not run.
     */
    var modalCount: Int by mutableIntStateOf(0)
        private set

    /** The app has just opened, which counts as the start of the first quiet stretch. */
    private var lastActivityMs = 0L
    private var lastTickMs = -PanelActivityThrottleMs

    fun openModal() {
        modalCount++
    }

    fun closeModal() {
        if (modalCount > 0) modalCount--
    }

    /** How long since the last input that counted. The watcher's countdown is measured on this. */
    fun quietMs(): Long = elapsedMs() - lastActivityMs

    /**
     * Deliberate input happened: a click, a drag, a scroll or a key. Hover never reaches here:
     * see the DJ app's root observer for what is let through.
     */
    fun notifyActivity() {
        val now = elapsedMs()
        // Recorded unconditionally, so no event can be dropped: the watcher re-reads quietMs()
        // when its wait expires and keeps waiting if this pushed the deadline out.
        lastActivityMs = now
        // Restarting the coroutine is the only thing worth skipping, and only while the panels
        // are already fully up. Fading or gone, the very next input has to wake them at once.
        if (alpha.targetValue >= 1f && now - lastTickMs < PanelActivityThrottleMs) return
        lastTickMs = now
        activityTick++
    }
}

/** Null wherever nothing fades the panels (the Orpheus app). */
val LocalPanelIdleFade = compositionLocalOf<PanelIdleFade?> { null }

/**
 * Owns [fade]'s countdown: one coroutine, restarted whenever
 * [PanelIdleFade.activityTick] or [enabled] changes. Renders nothing and reads nothing else, so
 * recomposing it per input never re-invokes a panel.
 *
 * While [enabled] is false, or any modal is open, alpha is pinned at 1f and nothing runs, which
 * is also what returns the panels the instant a viz that does not opt in is selected.
 */
@Composable
fun PanelIdleFadeWatcher(fade: PanelIdleFade?, enabled: Boolean) {
    if (fade == null) return
    val running = enabled && fade.modalCount == 0
    LaunchedEffect(fade.activityTick, running) { fade.runIdleCycle(running) }
}

/**
 * One bring-back-then-wait-then-fade cycle. Its own function rather than a lambda inside the
 * watcher so the timing can be driven by a virtual clock in a test.
 */
suspend fun PanelIdleFade.runIdleCycle(running: Boolean) {
    if (!running) {
        alpha.snapTo(1f)
        return
    }
    // Animating rather than snapping back: unlike the TV focus border this is the whole UI
    // returning, and a short animation reads as an answer rather than a flash.
    if (alpha.value < 1f) alpha.animateTo(1f, tween(PanelFadeInMs))
    // Waits out the quiet time that is actually left, re-checking each time. A throttled event
    // (mid-drag) does not restart this coroutine but it does move the deadline, and this is what
    // makes that safe: nothing fades early because an event chose not to interrupt.
    var left = PanelIdleTimeoutMs - quietMs()
    while (left > 0L) {
        delay(left)
        left = PanelIdleTimeoutMs - quietMs()
    }
    alpha.animateTo(0f, tween(PanelFadeOutMs))
}

/**
 * Applies the fade in the layer phase, the way `tvFocusRegionBorder` reads its own alpha in the
 * draw phase: the panels underneath are never recomposed by the animation.
 */
fun Modifier.panelIdleFade(fade: PanelIdleFade?): Modifier =
    if (fade == null) this else graphicsLayer { alpha = fade.alpha.value }

/**
 * Eats the one gesture that brings faded panels back, so the knob under the pointer is not also
 * turned by it. Covers the stage only, leaving the header and the nav live throughout, and skips
 * the chrome the stage reports inside it (the tabletop header, the phone bar's raised dome).
 *
 * Present the whole time the panels are anything less than fully drawn, not only once they are
 * invisible: a control half way through a 600 ms fade is barely there and must not be operable,
 * and a control fading back in is not yet aimed at. The cost is that the second click of a very
 * fast double click can land inside the 150 ms fade in and be eaten; a third of the old fade out
 * window was worse.
 *
 * Since hovering no longer wakes anything, this is what every desktop wake goes through. A drag
 * that starts here is dead for its whole length even after the overlay leaves: the node beneath
 * never saw the press, so nothing downstream can match an `awaitFirstDown`.
 *
 * A plain Box in the normal tree rather than a Popup on purpose: adding and removing a popup
 * layer off a layout-driven value is what disposes a RootNodeOwner out from under Compose.
 *
 * Accessibility: the blockers carry no semantics, and the faded panels keep theirs, so explore by
 * touch still finds them and the touch itself wakes them. The fade should be skipped outright
 * while a screen reader is running; that needs a platform flag this project does not have yet.
 */
@Composable
fun PanelWakeOverlay(fade: PanelIdleFade?, stage: VizStage?, enabled: Boolean) {
    if (fade == null || stage == null || !enabled) return
    // derivedStateOf: alpha changes every frame of the fade, this boolean twice a cycle.
    val covering by remember(fade) { derivedStateOf { fade.alpha.value < 1f } }
    val bounds = stage.bounds
    if (!covering || bounds == null || bounds.isEmpty) return
    wakeBlockers(bounds, listOf(stage.chromeBand, stage.chromeOverhang)).forEach { rect -> WakeBlocker(rect) }
}

/**
 * The stage less every piece of chrome in it that does not fade with the panels, so a tap on
 * visible chrome is never eaten. A rectangle cannot have a hole, so each hole splits every piece
 * it touches into up to four: above it, below it, and left and right of it across its rows.
 */
internal fun wakeBlockers(stage: Rect, holes: List<Rect?>): List<Rect> =
    holes.fold(listOf(stage)) { pieces, hole ->
        if (hole == null || hole.isEmpty) pieces else pieces.flatMap { it.around(hole) }
    }

private fun Rect.around(hole: Rect): List<Rect> {
    if (!overlaps(hole)) return listOf(this)
    val holeTop = hole.top.coerceIn(top, bottom)
    val holeBottom = hole.bottom.coerceIn(top, bottom)
    return listOf(
        Rect(left, top, right, holeTop),
        Rect(left, holeBottom, right, bottom),
        Rect(left, holeTop, hole.left.coerceIn(left, right), holeBottom),
        Rect(hole.right.coerceIn(left, right), holeTop, right, holeBottom),
    ).filterNot { it.isEmpty }
}

/** One rectangle of dead input over the faded panels. */
@Composable
private fun WakeBlocker(rect: Rect) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            // Never an accessibility target: it has nothing to say, and a screen reader stopping
            // on it would report a control where the user can see none.
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/**
 * The keys that activate whatever holds focus: a remote's select and both Enters.
 *
 * The space bar activates a focused control too, and is deliberately left out. Compose offers no
 * way at the root or the window to tell that the focused node is a text field, so swallowing it
 * would drop a space out of a sentence after a three-second pause in typing. Losing one Enter in a
 * chat box is a smaller price than a word run together.
 */
private val PanelConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The one key rule, shared by every path that sees a key: the DJ root modifier, the television
 * chrome and the desktop window hook. Records the press as activity, and returns true only for a
 * confirm key pressed while the panels are less than fully drawn, so the press that brings them
 * back cannot also fire a control nobody can see. Everything else is passed on, because D-pad
 * moves, synth keys and typing must never be swallowed.
 *
 * Two paths seeing one press is safe, which matters on desktop where the window hook and the root
 * modifier both run. A swallowed event never reaches the second path, and a second
 * [PanelIdleFade.notifyActivity] at the same instant records the same quiet time, so nothing
 * observable happens twice.
 */
fun PanelIdleFade?.handlePanelKey(event: KeyEvent, enabled: Boolean = true): Boolean {
    if (this == null || !enabled) return false
    val down = event.type == KeyEventType.KeyDown
    if (down) notifyActivity()
    // Both halves of the press, so swallowing the down does not leave a stray up behind. Reading
    // alpha after notifyActivity is deliberate: the animation has not moved yet, so the very
    // press that wakes the panels is the one held back.
    if (!down && event.type != KeyEventType.KeyUp) return false
    return alpha.value < 1f && event.key in PanelConfirmKeys
}

/**
 * Lets a host window hand keys to the fade before Compose routes them. Desktop key events travel
 * the focus path, so a freshly launched window that nobody has clicked never reaches the root
 * modifier and no key would bring the panels back.
 */
@Stable
class PanelKeyBridge {
    private var handler: ((KeyEvent) -> Boolean)? = null

    /** The host window's `onPreviewKeyEvent`; true means the key was swallowed. */
    fun onKeyEvent(event: KeyEvent): Boolean = handler?.invoke(event) ?: false

    /** Wired up by whoever owns the fade, and cleared when that composition goes. */
    fun connect(handler: ((KeyEvent) -> Boolean)?) {
        this.handler = handler
    }
}

/** Holds the panels up for as long as this composable is in the tree. */
@Composable
fun KeepPanelsAwake(fade: PanelIdleFade?) {
    if (fade == null) return
    DisposableEffect(fade) {
        fade.openModal()
        onDispose { fade.closeModal() }
    }
}

/** Milliseconds since the holder was built, from the one clock every target has. */
private fun monotonicMs(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
