package org.balch.orpheus.features.cowbell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import com.diamondedge.logging.logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.balch.orpheus.features.cowbell.motion.JoltWindow
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.widgets.BenderSpringEasing
import org.balch.orpheus.ui.widgets.benderSpringStages
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** How far back a press looks at the pointer's movement to judge its speed. */
private const val SPEED_WINDOW_MS = 40L

/** A drag faster than this strikes again as it travels. */
private const val SWIPE_STRIKE_SPEED = 500f

/** Travel between swipe strikes. */
private const val SWIPE_STRIKE_STEP_DP = 32f

/** The accelerometer is read over this long before the panel reports a touch, which it does late. */
private const val JOLT_LOOKBACK_MS = 40L

private val log = logging("CowbellGestures")

/** Recent pointer positions, for the speed over the last [SPEED_WINDOW_MS]. */
internal class PointerSpeedWindow {
    private val times = LongArray(16)
    private val xs = FloatArray(16)
    private val ys = FloatArray(16)
    private var count = 0
    private var head = 0

    fun add(timeMillis: Long, position: Offset) {
        times[head] = timeMillis
        xs[head] = position.x
        ys[head] = position.y
        head = (head + 1) % times.size
        if (count < times.size) count++
    }

    fun clear() {
        count = 0
    }

    /** Path length over the window ending at [nowMillis], per second, in px. Zero if the pointer sat still. */
    fun pxPerSecond(nowMillis: Long): Float {
        var length = 0f
        var oldest = nowMillis
        var prevX = 0f
        var prevY = 0f
        var seen = 0
        // Newest to oldest.
        for (i in 0 until count) {
            val idx = (head - 1 - i + times.size) % times.size
            if (nowMillis - times[idx] > SPEED_WINDOW_MS) break
            if (seen > 0) {
                val dx = prevX - xs[idx]
                val dy = prevY - ys[idx]
                length += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            prevX = xs[idx]
            prevY = ys[idx]
            oldest = times[idx]
            seen++
        }
        if (seen < 2) return 0f
        return length * 1000f / (nowMillis - oldest).coerceAtLeast(1L)
    }
}

/**
 * The bell's pointer handling. It consumes every event it gets.
 *
 * A primary press strikes in TAP and holds in SYNTH. In SHAKE it kicks the fill toward the press
 * instead, and the mouse over the bell tilts it and shoves it as it moves ([shake]). A second
 * touch or the right mouse button bends, over a held note or alone, and springs back on release;
 * in SHAKE the bend is also the fill's plunger. A press that ends any other way (the gesture is
 * cancelled, or an ancestor takes the pointer) still releases.
 */
internal fun Modifier.cowbellBellGestures(
    actions: State<CowbellPanelActions>,
    mode: State<CowbellMode>,
    bellBounds: State<Rect>,
    restartKey: Any?,
    onStruck: (at: Offset, velocity: Float) -> Unit,
    shake: ShakeInput = NoShakeInput,
    jolts: JoltWindow? = null,
    contact: ContactCalibration? = null,
): Modifier = pointerInput(restartKey) {
    coroutineScope {
        // The caller's calibration outlives a restart of this block (a focus loss), keeping its history.
        val gesture = BellGesture(
            this@pointerInput, this, actions, mode, bellBounds, onStruck, shake, jolts, contact ?: ContactCalibration(),
        )
        try {
            awaitPointerEventScope {
                while (true) gesture.handle(awaitPointerEvent())
            }
        } finally {
            gesture.cancel()
        }
    }
}

private class BellGesture(
    private val density: PointerInputScope,
    private val scope: CoroutineScope,
    private val actions: State<CowbellPanelActions>,
    private val mode: State<CowbellMode>,
    private val bounds: State<Rect>,
    private val onStruck: (Offset, Float) -> Unit,
    private val shake: ShakeInput,
    private val jolts: JoltWindow?,
    private val contact: ContactCalibration,
) {
    private val hover = PointerSpeedWindow()
    private val drag = PointerSpeedWindow()
    private var primary: PointerId? = null
    private var swipeTravelDp = 0f

    /** The press's jolt-and-contact strength as a velocity, 0 when nothing was measured. */
    private var pressVelocity = 0f

    /** A bend is pulled: by a second touch, or by the mouse's right button on any pointer. */
    private var bending = false
    private var bendPointer: PointerId? = null
    private var bendStartY = 0f
    private var bendBase = 0f
    private var bend = 0f
    private var spring: Job? = null

    // The mouse's buttons as of the last event. One mouse pointer carries both, so its presses
    // and releases are button changes, not pointer downs and ups.
    private var lastMouse = Offset.Unspecified
    private var mousePrimary = false
    private var mouseSecondary = false

    fun handle(event: PointerEvent) {
        val takenElsewhere = event.changes.any { it.isConsumed && (it.id == primary || it.id == bendPointer) }
        // Arrived consumed: someone above took it, so it only updates the mouse buttons below.
        val foreign = event.changes.filter { it.isConsumed }.map { it.id }.toSet()
        event.changes.forEach { it.consume() }
        if (takenElsewhere) cancel()
        for (change in event.changes) {
            val acts = !takenElsewhere && change.id !in foreign
            if (change.type == PointerType.Mouse) mouse(change, event, acts) else if (acts) touch(change, event)
        }
    }

    /** Lets go of whatever is held, without the spring. Safe with nothing held. */
    fun cancel() {
        if (primary != null) {
            primary = null
            actions.value.onRelease()
        }
        // Not spring.isActive: a spring cancelled along with this gesture is no longer active.
        if (bending || spring != null) {
            spring?.cancel()
            spring = null
            bending = false
            bendPointer = null
            bend = 0f
            shake.plunge(0f)
            actions.value.onBendDone()
        }
    }

    /**
     * Left strikes or holds; right bends, alone or over a held left. Right first then left also
     * plays, and the pointer's motion keeps bending while the right button is down.
     */
    private fun mouse(change: PointerInputChange, event: PointerEvent, acts: Boolean) {
        val primaryNow = change.pressed && event.buttons.isPrimaryPressed
        val secondaryNow = change.pressed && event.buttons.isSecondaryPressed
        // A new press only on a Press event: a fresh gesture must not read a held button as one.
        val pressing = event.type == PointerEventType.Press
        val primaryUp = mousePrimary && !primaryNow
        val secondaryUp = mouseSecondary && !secondaryNow
        val primaryDown = !mousePrimary && primaryNow && pressing
        val secondaryDown = !mouseSecondary && secondaryNow && pressing
        mousePrimary = primaryNow
        mouseSecondary = secondaryNow
        if (!acts) return

        if (secondaryUp && bending && bendPointer == change.id) releaseBend()
        if (primaryUp && primary == change.id) {
            primary = null
            actions.value.onRelease()
        }
        val moved = change.position != change.previousPosition
        // A hovering pointer has no previous position of its own, so keep it here.
        val before = lastMouse
        lastMouse = if (event.type == PointerEventType.Exit) Offset.Unspecified else change.position
        if (event.type != PointerEventType.Exit) tiltFrom(change.position)
        if (before.isSpecified && change.position != before && mode.value == CowbellMode.SHAKE) {
            val d = change.position - before
            shake.kick(d.x / density.density, d.y / density.density)
        }
        if (moved && change.pressed) moved(change)
        if (secondaryDown && !bending) startBend(change)
        if (primaryDown && primary == null) press(change, event)
        // Every hover event counts, still or not: the first one into the bell has no motion.
        if (!change.pressed && !primaryUp && !secondaryUp) hover.add(change.uptimeMillis, change.position)
    }

    /** The first finger strikes or holds; a second one bends. */
    private fun touch(change: PointerInputChange, event: PointerEvent) {
        when {
            change.changedToDownIgnoreConsumed() -> when {
                primary == null -> press(change, event)
                !bending -> startBend(change)
            }
            change.changedToUpIgnoreConsumed() -> when {
                change.id == primary -> {
                    primary = null
                    actions.value.onRelease()
                }
                bending && change.id == bendPointer -> releaseBend()
            }
            change.pressed && change.position != change.previousPosition -> moved(change)
        }
    }

    /** A bending pointer bends, even when it also holds the note; otherwise the primary drags. */
    private fun moved(change: PointerInputChange) {
        when {
            bending && change.id == bendPointer -> {
                bend = (bendBase + (bendStartY - change.position.y) / bounds.value.height).coerceIn(-1f, 1f)
                shake.plunge(bend)
                actions.value.onBend(bend)
            }
            change.id == primary -> drag(change)
        }
    }

    private fun press(change: PointerInputChange, event: PointerEvent) {
        primary = change.id
        pressVelocity = 0f
        val (x, y) = unit(change.position)
        if (mode.value == CowbellMode.SYNTH) {
            actions.value.onHold(x, y)
            onStruck(change.position, HOLD_RIPPLE_VELOCITY)
        } else if (mode.value == CowbellMode.SHAKE) {
            pushToward(change.position)
        } else {
            val fused = fuse(change, event)
            if (fused != null) pressVelocity = tapVelocity(fused, 0f)
            val velocity = tapVelocity(fused, hover.pxPerSecond(change.uptimeMillis) / density.density)
            actions.value.onStrike(velocity, x, y)
            onStruck(change.position, velocity)
        }
        drag.clear()
        drag.add(change.uptimeMillis, change.position)
        swipeTravelDp = 0f
    }

    /**
     * A touch's strength from the jolt it caused and the size of the fingertip, or null with
     * neither. Never waits: a jolt that has not arrived yet, or one under the floor, is no jolt.
     */
    private fun fuse(change: PointerInputChange, event: PointerEvent): Float? {
        val window = jolts
        val now = change.uptimeMillis
        val jolt = if (window == null) null else joltOrNull(window.peak(now - JOLT_LOOKBACK_MS, now))
        val major = event.contactSize(change)
        val size = if (major == null) null else contact.norm(major)
        if (window != null) {
            log.debug { "tap jolt ${window.lastPeakAtMillis.takeIf { it >= 0 }?.let { it - now }} ms from touch, norm $jolt, size $size" }
        }
        return fusedVelocity(jolt, size)
    }

    private fun drag(change: PointerInputChange) {
        // A SHAKE drag only shakes, so a fling throws the fill; it plays and holds nothing.
        // The mouse kicks on every move in mouse(), so only a finger kicks here.
        if (mode.value == CowbellMode.SHAKE) {
            if (change.type != PointerType.Mouse) {
                val d = change.position - change.previousPosition
                shake.kick(d.x / density.density * TOUCH_KICK_GAIN, d.y / density.density * TOUCH_KICK_GAIN)
            }
            return
        }
        val (x, y) = unit(change.position)
        actions.value.onMove(x, y)
        drag.add(change.uptimeMillis, change.position)
        if (mode.value == CowbellMode.SYNTH) return

        val speed = drag.pxPerSecond(change.uptimeMillis) / density.density
        if (speed <= SWIPE_STRIKE_SPEED) {
            swipeTravelDp = 0f
            return
        }
        swipeTravelDp += (change.position - change.previousPosition).getDistance() / density.density
        while (swipeTravelDp >= SWIPE_STRIKE_STEP_DP) {
            swipeTravelDp -= SWIPE_STRIKE_STEP_DP
            val velocity = max(velocityFromPointerSpeed(speed), pressVelocity)
            actions.value.onStrike(velocity, x, y)
            onStruck(change.position, velocity)
        }
    }

    private fun startBend(change: PointerInputChange) {
        // A new pull during the spring-back starts from wherever the spring had got to.
        spring?.cancel()
        spring = null
        bending = true
        bendPointer = change.id
        bendStartY = change.position.y
        bendBase = bend
    }

    private fun releaseBend() {
        bending = false
        bendPointer = null
        val start = bend
        shake.release(start)
        spring = scope.launch {
            val anim = Animatable(start)
            for (stage in benderSpringStages(start, abs(start))) {
                anim.animateTo(stage.target, tween(stage.durationMillis, easing = BenderSpringEasing)) {
                    bend = value
                    actions.value.onBend(value)
                }
            }
            bend = 0f
            spring = null
            actions.value.onBendDone()
        }
    }

    /** The mouse over the bell box, each axis -1..1 from the centre. */
    private fun tiltFrom(position: Offset) {
        val w = density.size.width.toFloat()
        val h = density.size.height.toFloat()
        if (w <= 0f || h <= 0f) return
        shake.pointerTilt((position.x / w - 0.5f) * 2f, (position.y / h - 0.5f) * 2f)
    }

    /** One push toward [position] from the bell box's centre, harder nearer its edge. */
    private fun pushToward(position: Offset) {
        val w = density.size.width.toFloat()
        val h = density.size.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dx = (position.x / w - 0.5f) * 2f
        val dy = (position.y / h - 0.5f) * 2f
        val len = sqrt(dx * dx + dy * dy)
        // Dead centre has no direction: push down, the way the fill rests.
        val ux = if (len > 1e-3f) dx / len else 0f
        val uy = if (len > 1e-3f) dy / len else 1f
        val power = PRESS_KICK_DP * (0.4f + 0.6f * len.coerceAtMost(1f))
        shake.kick(ux * power, uy * power)
    }

    private fun unit(position: Offset): Pair<Float, Float> {
        val b = bounds.value
        if (b.width <= 0f || b.height <= 0f) return 0.5f to 0.5f
        return ((position.x - b.left) / b.width).coerceIn(0f, 1f) to ((position.y - b.top) / b.height).coerceIn(0f, 1f)
    }

    companion object {
        /** A SYNTH hold has no strike velocity; its ripple uses a middling one. */
        const val HOLD_RIPPLE_VELOCITY = 0.6f
    }
}
