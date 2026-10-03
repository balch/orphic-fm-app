package org.balch.orpheus.djapp.playlist

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlin.math.sin

/** The ball's size over the stage. */
internal val RevealDiameter = 160.dp

// The reveal's beats. The ball rises, shakes, and turns over; the phrase lifts out of the die, grows
// to be read, and holds. Then the sheet opens, the ball sinks into the dome, and the phrase flies to
// the header's line so the eye knows where it lives now.
private const val ShakeMs = 520
private const val TurnHalfMs = 170
private const val PhraseHoldMs = 1_700L
private const val SinkMs = 300
private const val FlightMs = 480
private const val FlipMs = 340

/** The shake's widest tilt and sideways shimmy, and how many full swings it makes, each smaller than the last. */
private const val ShakeDegrees = 18f
private val ShakeShift = 6.dp
private const val ShakeCycles = 3f
private const val TwoPi = 6.2831855f

/**
 * The phrase over the stage is the header's own line, italic and all, at [PhraseSize] where the
 * header's is small: it lands as itself, only shrunk. It starts at [RiseStartScale] of that, the size
 * the die's text used to be, so it reads as lifting out of the die.
 */
private val PhraseSize = 22.sp
private const val RiseStartScale = 0.34f

/** Between the ball's top and the phrase, and the least between the stage's top and it. */
private val PhraseGap = 20.dp
private val PhraseMargin = 16.dp

/** The dark plate under the phrase over the stage, so it reads over any background. It fades as the phrase flies. */
private val BackingColor = Color(0xFF02051A).copy(alpha = 0.72f)
private val BackingPadX = 14.dp
private val BackingPadY = 8.dp
private val BackingRadius = 14.dp

/** The shake at [progress] 0 to 1, from -1 to 1 and dying away to level: the tilt and the shimmy both follow it. */
private fun shakeSwing(progress: Float): Float = (1f - progress) * sin(progress * ShakeCycles * TwoPi)

/**
 * Plays [state]'s reveal over the whole screen: the ball rises from the dome to the stage's centre,
 * shakes, and turns over to its die; the phrase lifts out and grows above it to be read. Then the
 * sheet opens, the ball sinks back into the dome, and the phrase flies to the sheet's header line.
 * The sink ends at the dome's centre and size, where the ring takes over the ball while the sheet is
 * open. When the sheet closes the ring turns the ball back into the dome; this only times that turn.
 *
 * Each motion is one float of state, written by the frame clock on the main thread and read only in
 * draw, in the layers below: a frame recomposes nothing and allocates nothing.
 */
@Composable
internal fun EightBallReveal(state: EightBallRevealState, modifier: Modifier = Modifier) {
    val phase = state.phase
    if (phase == RevealPhase.Closing) {
        LaunchedEffect(Unit) {
            animate(0f, 1f, animationSpec = tween(FlipMs, easing = FastOutSlowInEasing)) { p, _ -> state.flip = p }
            state.finish()
        }
        return
    }
    val from = when (phase) {
        is RevealPhase.Showing -> phase.from
        is RevealPhase.Sinking -> phase.from
        else -> return
    }
    val density = LocalDensity.current
    val endPx = with(density) { RevealDiameter.toPx() }
    val gapPx = with(density) { PhraseGap.toPx() }
    val marginPx = with(density) { PhraseMargin.toPx() }
    val padXPx = with(density) { BackingPadX.toPx() }
    val padYPx = with(density) { BackingPadY.toPx() }
    val radiusPx = with(density) { BackingRadius.toPx() }
    val shiftPx = with(density) { ShakeShift.toPx() }
    val header = MaterialTheme.typography.bodySmall
    val phraseStyle = remember(header) {
        header.copy(
            fontSize = PhraseSize,
            lineHeight = PhraseSize * (header.lineHeight.value / header.fontSize.value),
            fontStyle = FontStyle.Italic,
        )
    }
    // How much smaller the header sets the phrase than the stage does: where the flight ends.
    val landedScale = header.fontSize.value / PhraseSize.value
    val quoted = remember(state.phrase) { "“${state.phrase}”" }
    val shake = remember { mutableFloatStateOf(0f) }
    // The face's turn: 0 to 0.5 takes the first face edge-on, 0.5 to 1 brings the second in.
    val turn = remember { mutableFloatStateOf(0f) }
    // The phrase: 0 in the die's window, 1 hovering above the ball. Its flight to the header is the state's.
    val rise = remember { mutableFloatStateOf(0f) }
    var face by remember { mutableStateOf<EightBallFace>(EightBallFace.Eight) }
    // This overlay's own bounds in root coordinates: its origin, and the stage when none was reported.
    var overlay by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(phase) {
        when (phase) {
            is RevealPhase.Showing -> {
                face = EightBallFace.Eight
                shake.floatValue = 0f
                turn.floatValue = 0f
                rise.floatValue = 0f
                state.flight = 0f
                animate(0f, 1f, animationSpec = spring(dampingRatio = 0.72f, stiffness = 300f)) { v, _ -> state.travel = v }
                animate(0f, 1f, animationSpec = tween(ShakeMs, easing = LinearEasing)) { v, _ -> shake.floatValue = v }
                animate(0f, 0.5f, animationSpec = tween(TurnHalfMs, easing = FastOutLinearInEasing)) { v, _ -> turn.floatValue = v }
                face = EightBallFace.Die
                animate(0.5f, 1f, animationSpec = tween(TurnHalfMs, easing = LinearOutSlowInEasing)) { v, _ -> turn.floatValue = v }
                animate(0f, 1f, animationSpec = spring(dampingRatio = 0.75f, stiffness = 220f)) { v, _ -> rise.floatValue = v }
                delay(PhraseHoldMs)
                state.handOver()
            }
            is RevealPhase.Sinking -> {
                // A tap mid-shake or mid-turn cut it short: the ball sinks level and its face whole.
                shake.floatValue = 0f
                turn.floatValue = 0f
                animate(state.travel, 0f, animationSpec = tween(SinkMs)) { v, _ -> state.travel = v }
                // The sheet has slid up by now, so the header's line is where it will stay.
                animate(0f, 1f, animationSpec = tween(FlightMs, easing = FastOutSlowInEasing)) { v, _ -> state.flight = v }
                state.finish()
            }
        }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { overlay = it.boundsInRoot() }) {
        EightBall(
            face = face,
            diameter = RevealDiameter,
            faceTurn = { turn.floatValue },
            modifier = Modifier
                .graphicsLayer {
                    val t = state.travel
                    val stage = state.stageBounds.takeUnless { it == Rect.Zero } ?: overlay
                    val centre = lerp(from.center, stage.center, t) - overlay.topLeft
                    val scale = (from.width + (endPx - from.width) * t) / endPx
                    scaleX = scale
                    scaleY = scale
                    val swing = shakeSwing(shake.floatValue)
                    translationX = centre.x - endPx / 2 + shiftPx * swing
                    translationY = centre.y - endPx / 2
                    rotationZ = ShakeDegrees * swing
                }
                .pointerInput(state) { detectTapGestures { state.handOver() } },
        )
        Text(
            text = quoted,
            style = phraseStyle,
            color = PhraseBlue,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                // Its own width however narrow the stage, placed at x = 0. Reporting a size past the constraints
                // makes Compose re-centre the content (shifting an over-wide phrase left), so report within them.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                    layout(placeable.width.coerceAtMost(constraints.maxWidth), placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val stage = state.stageBounds.takeUnless { it == Rect.Zero } ?: overlay
                    val w = size.width
                    val h = size.height
                    val r = rise.floatValue
                    val f = state.flight
                    // Hovering: as large as fits the stage's width, standing clear above the ball and the stage's top.
                    val hoverScale = ((stage.width * 0.92f) / (w + 2f * padXPx)).coerceAtMost(1f)
                    val hoverY = (stage.center.y - endPx / 2f - gapPx - h * hoverScale / 2f)
                        .coerceAtLeast(stage.top + marginPx + h * hoverScale / 2f)
                    var x = stage.center.x
                    var y = lerp(stage.center.y, hoverY, r)
                    var s = lerp(RiseStartScale, hoverScale, r)
                    // The header's line starts at its left edge; until the sheet has laid it out there is nowhere to fly to.
                    val anchored = !state.phraseX.isNaN()
                    if (anchored) {
                        x = lerp(x, state.phraseX + w * landedScale / 2f, f)
                        y = lerp(y, state.phraseY, f)
                        s = lerp(s, landedScale, f)
                    }
                    scaleX = s
                    scaleY = s
                    translationX = x - overlay.left - w / 2f
                    translationY = y - overlay.top - h / 2f
                    // Lifting out of the die it fades in; landing, it gives way to the header's own copy.
                    val fadeIn = (r * 3f).coerceIn(0f, 1f)
                    alpha = fadeIn * (1f - if (anchored) state.headerPhraseAlpha else f)
                }
                .drawBehind {
                    val a = 1f - state.flight
                    if (a <= 0f) return@drawBehind
                    drawRoundRect(
                        BackingColor.copy(alpha = BackingColor.alpha * a),
                        topLeft = Offset(-padXPx, -padYPx),
                        size = Size(size.width + 2f * padXPx, size.height + 2f * padYPx),
                        cornerRadius = CornerRadius(radiusPx),
                    )
                }
                .pointerInput(state) { detectTapGestures { state.handOver() } },
        )
    }
}
