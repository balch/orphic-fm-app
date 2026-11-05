package org.balch.orpheus.features.cowbell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.balch.orpheus.features.cowbell.bell3d.BellCamera
import org.balch.orpheus.features.cowbell.bell3d.BellProjector
import org.balch.orpheus.features.cowbell.bell3d.BellTransition
import org.balch.orpheus.features.cowbell.bell3d.FillPainter
import org.balch.orpheus.features.cowbell.bell3d.LiquidRenderer
import org.balch.orpheus.features.cowbell.bell3d.PROTOTYPE_PX_PER_UNIT
import org.balch.orpheus.features.cowbell.bell3d.ShardRenderer
import org.balch.orpheus.features.cowbell.bell3d.camera
import org.balch.orpheus.features.cowbell.bell3d.drawBellMesh
import org.balch.orpheus.features.cowbell.bell3d.drawShardMorph
import org.balch.orpheus.features.cowbell.bell3d.fittedCamera
import org.balch.orpheus.features.cowbell.bell3d.forMode
import org.balch.orpheus.features.cowbell.bell3d.isDone
import org.balch.orpheus.features.cowbell.bell3d.liquidAlpha
import org.balch.orpheus.features.cowbell.bell3d.liquidFrame
import org.balch.orpheus.features.cowbell.bell3d.meshAlpha
import org.balch.orpheus.features.cowbell.bell3d.morphSeconds
import org.balch.orpheus.features.cowbell.bell3d.paddedMeshFrame
import org.balch.orpheus.features.cowbell.bell3d.progress
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.max
import kotlin.random.Random

internal const val CowbellBellTag = "cowbell-bell"

private const val FLASH_MILLIS = 250
private const val RIPPLE_MILLIS = 450
private const val MAX_RIPPLES = 6

internal val NoLevel: StateFlow<Float> = MutableStateFlow(0f)

private class Ripple(val center: Offset, val velocity: Float) {
    val progress = Animatable(0f)
}

/** The strike flash and ripples. Everything here is read in the draw phase only. */
@Stable
private class BellVisuals(private val scope: CoroutineScope) {
    val flash = Animatable(0f)
    val ripples = mutableStateListOf<Ripple>()

    fun strike(at: Offset, velocity: Float) {
        scope.launch {
            flash.snapTo(max(flash.value, 0.4f + 0.6f * velocity))
            flash.animateTo(0f, tween(FLASH_MILLIS, easing = LinearOutSlowInEasing))
        }
        if (ripples.size >= MAX_RIPPLES) ripples.removeAt(0)
        val ripple = Ripple(at, velocity)
        ripples += ripple
        scope.launch {
            ripple.progress.animateTo(1f, tween(RIPPLE_MILLIS, easing = LinearOutSlowInEasing))
            ripples.remove(ripple)
        }
    }
}

/** Seconds since the bell entered composition, and the camera move. Plain fields: the frame loop must not touch snapshots. */
private class BellClock {
    var startNanos = -1L
    var seconds = 0.0
    var mode: CowbellMode? = null
    var transition: BellTransition? = null

    /** Turns toward [to] from wherever the bell is now, mid move or at rest. */
    fun turnTo(to: CowbellMode, durationSeconds: Double, cameraFor: (CowbellMode) -> BellCamera) {
        val from = mode ?: return
        if (from == to) return
        val camera = transition?.takeUnless { it.isDone(seconds) }?.camera(seconds) ?: cameraFor(from)
        transition = BellTransition(camera, cameraFor(to), seconds, durationSeconds)
    }
}

/**
 * Zooms each mode's resting bell until it fills [fraction] of the box. Shake fits a box
 * [shakeInsetY] shorter at top and bottom and rides up half of that, so the bell and the label
 * under it centre as a pair.
 */
@Immutable
internal data class BellFit(val fraction: Float, val shakeInsetY: Dp = 0.dp)

/** Each mode's resting camera in a box of [size], fitted by [fit] when there is one. */
private fun Density.restingCameras(size: Size, fit: BellFit?): Map<CowbellMode, BellCamera> =
    CowbellMode.entries.associateWith { mode ->
        val base = BellCamera.forMode(mode)
        if (fit == null || size.width < 1f || size.height < 1f) return@associateWith base
        val inset = if (mode == CowbellMode.SHAKE) fit.shakeInsetY.toPx() else 0f
        val fitted = fittedCamera(size, base, fit.fraction, Size(size.width, max(1f, size.height - 2f * inset)))
        if (inset > 0f) fitted.copy(shiftY = -inset / 2f) else fitted
    }

/**
 * The playable 3D bell, one camera per mode. [bend] is read only while drawing, so a drag-rate
 * bend redraws the bell and recomposes nothing. [outLevel] is the output peak, 0..1, for the rim.
 * [random] seeds the fill's pieces; only a test passes anything but the default. [keyCursor] is
 * where the D-pad strikes; a focused bell draws a ring there. [fit], when set, zooms the bell to
 * fill its box; it is read outside composition, so an animated fit resizes without recomposing.
 * Without it the bell keeps the fitted-to-every-view scale. [onRestingBounds] hears
 * where the resting bell is drawn, in this box's pixels, whenever a size or mode change moves it.
 */
@Composable
internal fun CowbellBell3D(
    mode: CowbellMode,
    actions: CowbellPanelActions,
    bend: () -> Float,
    modifier: Modifier = Modifier,
    outLevel: StateFlow<Float> = NoLevel,
    fill: CowbellFill = CowbellFill.MARBLE,
    morphStyle: BellMorphStyle = BellMorphStyle.SHARDS,
    random: Random = Random.Default,
    keyCursor: BellKeyCursor = remember { BellKeyCursor() },
    fit: (() -> BellFit)? = null,
    onRestingBounds: ((Rect) -> Unit)? = null,
    motionOn: Boolean = false,
) {
    val latestStyle = rememberUpdatedState(morphStyle)
    val latestActions = rememberUpdatedState(actions)
    val latestMode = rememberUpdatedState(mode)
    val latestFill = rememberUpdatedState(fill)
    val latestMotionOn = rememberUpdatedState(motionOn)
    val latestBend by rememberUpdatedState(bend)
    val latestDensity = rememberUpdatedState(LocalDensity.current)
    val level = outLevel.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val motion = rememberCowbellMotion()
    LaunchedEffect(motion.available) { latestActions.value.onMotionAvailable(motion.available) }
    // Leaving the screen lets go of STRUCTURE: a later MIDI or AI strike must not sound at the
    // tilt this bell last wrote.
    DisposableEffect(motion) {
        onDispose {
            if (motion.available) {
                latestActions.value.onTilt(0f)
                latestActions.value.onMotionAvailable(false)
            }
        }
    }
    // Tilt, kicks and the SHAKE fill: plain fields, stepped once per frame below.
    val rig = remember(motion, random) { ShakeRig(motion, random) }
    val fillPainter = remember { FillPainter() }
    val visuals = remember(scope) { BellVisuals(scope) }
    val boxSize = remember { mutableStateOf(Size.Zero) }
    val latestFit = rememberUpdatedState(fit)
    val cameras = remember { derivedStateOf { latestDensity.value.restingCameras(boxSize.value, latestFit.value?.invoke()) } }
    val cameraFor = remember { { m: CowbellMode -> cameras.value[m] ?: BellCamera.forMode(m) } }
    // The resting camera at no pull: a size or mode change moves these, a frame never does.
    val bounds = remember {
        derivedStateOf {
            latestDensity.value.paddedMeshFrame(boxSize.value, cameraFor(latestMode.value))?.bounds ?: Rect.Zero
        }
    }
    if (onRestingBounds != null) {
        val latestOnBounds = rememberUpdatedState(onRestingBounds)
        LaunchedEffect(bounds) { snapshotFlow { bounds.value }.collect { latestOnBounds.value(it) } }
    }
    // Losing the window mid-press restarts the gesture, whose cancel path lets go, and lets go of
    // a key hold too (its KeyUp goes to the other window). Only a loss counts: the click that
    // focuses the window must not end the press it starts.
    val windowInfo = LocalWindowInfo.current
    var focusLosses by remember { mutableIntStateOf(0) }
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.drop(1).filter { !it }.collect {
            focusLosses++
            keyCursor.letGo(latestActions.value)
        }
    }
    val clock = remember { BellClock() }
    // Outlives a gesture restart, so the last 32 touches' sizes still calibrate the next.
    val contact = remember { ContactCalibration() }
    val liquid = remember { LiquidRenderer() }
    DisposableEffect(liquid) { onDispose { liquid.dispose() } }
    val shards = remember { ShardRenderer() }
    // Runs before this frame draws, so the new camera never blinks in at rest. Frames do not
    // recompose, so this runs only on recomposition and turns only when the mode changed.
    SideEffect {
        clock.turnTo(mode, morphSeconds(morphStyle, liquid.isSupported(), shards.isSupported()), cameraFor)
        clock.mode = mode
    }
    // Read only in the draw phase, so each frame redraws the bell and recomposes nothing.
    val frameTick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(clock) {
        while (true) {
            withFrameNanos { now ->
                if (clock.startNanos < 0L) clock.startNanos = now
                clock.seconds = (now - clock.startNanos) / 1e9
                if (clock.transition?.isDone(clock.seconds) == true) clock.transition = null
                val m = clock.mode ?: latestMode.value
                rig.frame(clock.seconds, m, latestFill.value, clock.transition != null, latestActions.value, cameraFor(m), latestMotionOn.value)
                frameTick.longValue = now
            }
        }
    }

    Spacer(
        modifier
            .testTag(CowbellBellTag)
            .semantics {
                contentDescription = "Cowbell"
                // No pointer, no speed: an accessibility click strikes at the hold velocity.
                onClick(label = "Strike") {
                    latestActions.value.onStrike(CowbellViewModel.HOLD_STRIKE_VELOCITY, 0.5f, 0.5f)
                    visuals.strike(bounds.value.center, CowbellViewModel.HOLD_STRIKE_VELOCITY)
                    true
                }
            }
            .onSizeChanged { boxSize.value = it.toSize() }
            .cowbellBellKeys(
                mode = mode,
                actions = actions,
                cursor = keyCursor,
                onStruck = { point, velocity ->
                    val b = bounds.value
                    visuals.strike(Offset(b.left + point.x * b.width, b.top + point.y * b.height), velocity)
                },
            )
            .cowbellBellGestures(
                actions = latestActions,
                mode = latestMode,
                bellBounds = bounds,
                restartKey = focusLosses,
                onStruck = visuals::strike,
                shake = rig,
                // Without sensors a still tap would read as a zero jolt, so desktop passes none.
                jolts = if (motion.available && motionOn) motion.jolts else null,
                contact = contact,
            )
            .drawBehind {
                frameTick.longValue
                val flash = max(visuals.flash.value, rig.flash)
                val glow = max(level.value.coerceIn(0f, 1f), flash)
                val now = clock.seconds
                val morph = clock.transition?.takeUnless { it.isDone(now) }
                if (morph != null) {
                    val burst = shards.takeIf { latestStyle.value == BellMorphStyle.SHARDS && it.isSupported() }
                    drawBellMorph(morph, now, now, latestBend(), flash, glow, liquid, burst)
                } else {
                    val camera = rig.camera ?: cameraFor(latestMode.value)
                    val pull = latestBend()
                    val padded = paddedMeshFrame(size, camera, pull)
                    val physics = rig.physics
                    if (padded != null) {
                        inset(padded.pad) {
                            // The fill sits in the mouth, under the rim glow.
                            val under: (DrawScope.() -> Unit)? = if (physics != null && latestMode.value == CowbellMode.SHAKE) {
                                { fillPainter.draw(this, latestFill.value, physics.particles, camera, pull, padded.frame, flash) }
                            } else {
                                null
                            }
                            drawBellMesh(padded.frame, now, flash, glow, belowRim = under)
                        }
                    }
                }
                for (ripple in visuals.ripples) drawRipple(ripple)
                if (keyCursor.focused) drawKeyRing(keyCursor.point, bounds.value)
            },
    )
}

/**
 * One frame of [transition] at [nowSeconds]. [shards] bursts the bell; else a supported [liquid]
 * fades the mesh into chrome and back; otherwise the mesh turns plainly. The liquid shares the
 * mesh's padded box so they line up.
 */
internal fun DrawScope.drawBellMorph(
    transition: BellTransition,
    nowSeconds: Double,
    blobSeconds: Double,
    pull: Float,
    flash: Float,
    glow: Float,
    liquid: LiquidRenderer?,
    shards: ShardRenderer? = null,
) {
    if (shards != null) return drawShardMorph(transition, nowSeconds, blobSeconds, pull, flash, glow, shards)
    val camera = transition.camera(nowSeconds)
    val p = transition.progress(nowSeconds)
    val padded = paddedMeshFrame(size, camera, pull) ?: return
    val pad = padded.pad
    inset(pad) {
        val liquidSeconds = (nowSeconds - transition.startSeconds).toFloat()
        val projector = BellProjector(size, camera)
        val brush = if (liquid != null && liquid.isSupported() && !projector.isEmpty) {
            liquid.brush(projector.scale / PROTOTYPE_PX_PER_UNIT, liquidSeconds, liquidFrame(projector, camera, p, liquidSeconds))
        } else {
            null
        }
        drawBellMesh(padded.frame, blobSeconds, flash, glow, alpha = if (brush != null) meshAlpha(p) else 1f)
        // Over the whole box, so the edge glow is not cut at the stroke pad.
        if (brush != null) {
            drawRect(brush, Offset(-pad, -pad), Size(size.width + 2f * pad, size.height + 2f * pad), alpha = liquidAlpha(p))
        }
    }
}

/** The D-pad's strike point, drawn only while the bell has focus. */
private fun DrawScope.drawKeyRing(point: Offset, bounds: Rect) {
    val at = Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)
    drawCircle(CowbellPanelColor, 12.dp.toPx(), at, style = Stroke(2.dp.toPx()))
    drawCircle(CowbellPanelColor, 2.dp.toPx(), at)
}

private fun DrawScope.drawRipple(ripple: Ripple) {
    val p = ripple.progress.value
    val radius = (10.dp.toPx() + 54.dp.toPx() * ripple.velocity) * p
    val fade = 1f - p
    drawCircle(OrpheusColors.neonCyan.copy(alpha = 0.12f * fade), radius, ripple.center)
    drawCircle(Color.White.copy(alpha = 0.7f * fade), radius, ripple.center, style = Stroke(2.dp.toPx()))
}
