package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared state holder for knob drag interaction. */
/** One D-pad press moves 2.5% of the range; 40 presses cross the whole knob. */
private const val StepFraction = 0.025f
private const val FineStepFraction = 0.005f

internal class KnobDragState(
    initialValue: Float,
    private val range: ClosedFloatingPointRange<Float>,
    private val sensitivity: Float,
) {
    var internalValue by mutableStateOf(initialValue)

    /**
     * Nudge by whole steps, for D-pad and arrow keys. Expressed as a fraction of the range
     * rather than pixels: a key press has no drag distance, and a value step also sidesteps
     * the density scaling that makes pixel deltas differ per screen.
     */
    fun applyStep(steps: Int, fine: Boolean = false): Float? {
        val fraction = if (fine) FineStepFraction else StepFraction
        val delta = steps * (range.endInclusive - range.start) * fraction
        val newValue = (internalValue + delta).coerceIn(range)
        return if (newValue != internalValue) {
            internalValue = newValue
            newValue
        } else null
    }

    fun applyDrag(dragAmount: Float, ctrlPressed: Boolean, fineTune: Boolean = false): Float? {
        val ctrlMultiplier = if (ctrlPressed) 20f else 1f
        val effectiveSensitivity = sensitivity * (if (fineTune) 10f else 1f) * ctrlMultiplier
        val delta = (-dragAmount) * (range.endInclusive - range.start) / effectiveSensitivity
        val newValue = (internalValue + delta).coerceIn(range)
        return if (newValue != internalValue) {
            internalValue = newValue
            newValue
        } else null
    }
}

@Composable
internal fun rememberKnobDragState(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    sensitivity: Float = 200f,
): KnobDragState {
    val state = remember(value) { KnobDragState(value, range, sensitivity) }
    return state
}

/**
 * The knob circle + arc drawing with drag interaction.
 * Shared between [RotaryKnob] and [HorizontalRotaryKnob].
 */
/**
 * Applies one step only while the knob is in adjust mode, and reports whether the key was
 * consumed. Outside adjust mode the arrow key is left alone so focus traversal still works.
 */
private fun stepIf(
    adjusting: Boolean,
    dragState: KnobDragState,
    steps: Int,
    fine: Boolean,
    onValueChange: (Float) -> Unit,
): Boolean {
    if (!adjusting) return false
    dragState.applyStep(steps, fine)?.let(onValueChange)
    return true
}

@Composable
internal fun RotaryKnobDial(
    dragState: KnobDragState,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    trackColor: Color = OrpheusColors.deepPurple,
    progressColor: Color = OrpheusColors.neonCyan,
    knobColor: Color = OrpheusColors.softPurple,
    indicatorColor: Color = OrpheusColors.neonCyan,
    isLearning: Boolean = false,
    enabled: Boolean = true,
    // Preview/render-harness seam only: forces the focus/adjust visuals without real input.
    // null (the default, used by every real call site) means "use live focus state" below.
    previewFocused: Boolean? = null,
    previewAdjusting: Boolean = false,
) {
    val sensitivity = 200f
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var liveFocused by remember { mutableStateOf(false) }
    // D-pad left/right is also how focus moves, so a knob may only consume those keys once
    // the user has explicitly entered adjust mode with select. Otherwise focus is trapped:
    // you can reach a knob with the remote and never leave it.
    var liveAdjusting by remember { mutableStateOf(false) }
    val isFocused = previewFocused ?: liveFocused
    val isAdjusting = if (previewFocused != null) previewAdjusting else liveAdjusting

    // TV: a thin ring is too subtle from couch distance and against a busy viz background, so
    // focus gets an opaque raised plate instead (see raisedAccentSurface's language). Gated so
    // every other platform's keyboard-focus ring (drawn below) is unchanged.
    val isTvFocusChrome = LocalTvFocusChrome.current
    val adjustPulseAlpha = if (isTvFocusChrome && isAdjusting) {
        val transition = rememberInfiniteTransition(label = "knobAdjustPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(550), repeatMode = RepeatMode.Reverse),
            label = "knobAdjustPulseAlpha",
        )
        alpha
    } else {
        0f
    }

    Box(modifier = modifier.size(size)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // D-pad and arrow keys drive the knob by value steps. Focusable comes after
                // onKeyEvent so the handler is in the chain for the node that takes focus.
                .onFocusChanged {
                    liveFocused = it.isFocused
                    if (!it.isFocused) liveAdjusting = false
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || isLearning || !enabled) {
                        return@onKeyEvent false
                    }
                    when (event.key) {
                        // Select toggles adjust mode; back leaves it without exiting the app.
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            liveAdjusting = !liveAdjusting
                            true
                        }
                        Key.Back, Key.Escape -> {
                            val wasAdjusting = liveAdjusting
                            liveAdjusting = false
                            wasAdjusting
                        }
                        Key.DirectionRight, Key.DirectionUp -> stepIf(
                            liveAdjusting, dragState, 1, event.isCtrlPressed, currentOnValueChange,
                        )
                        Key.DirectionLeft, Key.DirectionDown -> stepIf(
                            liveAdjusting, dragState, -1, event.isCtrlPressed, currentOnValueChange,
                        )
                        else -> false
                    }
                }
                .focusable(enabled = enabled && !isLearning)
                .pointerInput(sensitivity, range, isLearning, enabled) {
                    if (isLearning || !enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var previousY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dragAmount = change.position.y - previousY
                            previousY = change.position.y
                            if (dragAmount != 0f) {
                                change.consume()
                                dragState.applyDrag(dragAmount, event.keyboardModifiers.isCtrlPressed)
                                    ?.let { currentOnValueChange(it) }
                            }
                        }
                    }
                }
        ) {
            val strokeWidth = size.toPx() * 0.1f
            val radius = (size.toPx() - strokeWidth) / 2
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            val arcSize = Size(radius * 2, radius * 2)
            val topLeft = Offset(center.x - radius, center.y - radius)

            val startAngle = 135f
            val sweepAngle = 270f

            // Focus treatment: a full circle outside the dial, so a D-pad selection is legible
            // from across a room rather than a hairline outline.
            if (isFocused && isTvFocusChrome) {
                // TV: raised opaque plate — lit-top/dark-bottom bevel fill plus a drop shadow —
                // rather than a thin ring, so it reads as "lifted" even over a bright/busy viz.
                // Adjusting uses progressColor and adds a pulsing outer glow so the two states
                // are unmistakably different: a static plate means the D-pad moves focus, a
                // pulsing glow means left/right now changes the value.
                val plateAccent = if (isAdjusting) progressColor else indicatorColor
                val plateRadius = radius + strokeWidth * 1.8f

                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = plateRadius,
                    center = center.copy(y = center.y + strokeWidth * 0.35f),
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            plateAccent.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.85f),
                        ),
                        startY = center.y - plateRadius,
                        endY = center.y + plateRadius,
                    ),
                    radius = plateRadius,
                    center = center,
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(plateAccent, Color.Black.copy(alpha = 0.6f)),
                        startY = center.y - plateRadius,
                        endY = center.y + plateRadius,
                    ),
                    radius = plateRadius,
                    center = center,
                    style = Stroke(width = strokeWidth * 0.35f),
                )
                if (isAdjusting) {
                    drawCircle(
                        color = progressColor.copy(alpha = adjustPulseAlpha),
                        radius = plateRadius + strokeWidth * 0.7f,
                        center = center,
                        style = Stroke(width = strokeWidth * 0.5f),
                    )
                }
            } else if (isFocused) {
                // Non-TV keyboard focus: unchanged from before this TV pass.
                drawCircle(
                    color = if (isAdjusting) progressColor else indicatorColor,
                    radius = radius + strokeWidth * 0.55f,
                    center = center,
                    style = Stroke(width = strokeWidth * (if (isAdjusting) 0.9f else 0.45f)),
                )
            }

            // Track Groove (Shadow)
            drawArc(
                color = Color.Black.copy(alpha = 0.5f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                color = trackColor.copy(alpha = 0.3f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Progress Arc with Glow
            val normalizedValue =
                (dragState.internalValue - range.start) / (range.endInclusive - range.start)
            val currentSweep = sweepAngle * normalizedValue

            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        progressColor.copy(alpha = 0.0f),
                        progressColor.copy(alpha = 0.6f)
                    ),
                    center = center
                ),
                startAngle = startAngle,
                sweepAngle = currentSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round)
            )

            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(progressColor.copy(alpha = 0.5f), progressColor),
                    center = center
                ),
                startAngle = startAngle,
                sweepAngle = currentSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Knob Body
            val knobRadius = radius * 0.7f
            val angleInDegrees = startAngle + currentSweep
            val angleInRadians = angleInDegrees.toDouble() * PI / 180.0

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                    center = center.copy(y = center.y + 4.dp.toPx()),
                    radius = knobRadius + 4.dp.toPx()
                ),
                radius = knobRadius + 4.dp.toPx(),
                center = center.copy(y = center.y + 4.dp.toPx())
            )

            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        knobColor.copy(alpha = 0.8f),
                        trackColor,
                        Color.Black
                    ),
                    start = Offset(center.x - knobRadius, center.y - knobRadius),
                    end = Offset(center.x + knobRadius, center.y + knobRadius)
                ),
                radius = knobRadius,
                center = center
            )

            drawCircle(
                style = Stroke(width = 2.dp.toPx()),
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.3f),
                        Color.Black.copy(alpha = 0.6f)
                    ),
                    start = Offset(center.x - knobRadius, center.y - knobRadius),
                    end = Offset(center.x + knobRadius, center.y + knobRadius)
                ),
                radius = knobRadius,
                center = center
            )

            // Indicator (Notch)
            val indicatorLength = knobRadius * 0.5f
            val endX = center.x + indicatorLength * cos(angleInRadians).toFloat()
            val endY = center.y + indicatorLength * sin(angleInRadians).toFloat()

            drawLine(
                color = indicatorColor,
                start = center,
                end = Offset(endX, endY),
                strokeWidth = strokeWidth * 0.8f,
                cap = StrokeCap.Round
            )

            drawCircle(
                color = indicatorColor,
                radius = strokeWidth * 0.6f,
                center = Offset(endX, endY)
            )
        }
    }
}

/** Fine-tune value text with precision drag (10x slower). */
@Composable
internal fun KnobValueText(
    dragState: KnobDragState,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    progressColor: Color,
    valueFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    val sensitivity = 200f
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    Text(
        text = valueFormatter(dragState.internalValue),
        style = MaterialTheme.typography.labelMedium,
        color = progressColor.lighten(0.3f),
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier.pointerInput(range, sensitivity) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var previousY = down.position.y
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val dragAmount = change.position.y - previousY
                    previousY = change.position.y
                    if (dragAmount != 0f) {
                        change.consume()
                        dragState.applyDrag(dragAmount, event.keyboardModifiers.isCtrlPressed, fineTune = true)
                            ?.let { currentOnValueChange(it) }
                    }
                }
            }
        }
    )
}

@Composable
@Preview
fun RotaryKnobPreview() {
    OrpheusTheme {
        RotaryKnob(
            label = "Volume",
            value = 0.5f,
            onValueChange = {}
        )
    }
}

typealias KnobValueFormatter = (Float) -> String

/**
 * A synth-style rotary knob control with vertical layout (knob above, label below).
 * Supports vertical drag interaction for precision.
 *
 * @param controlId Optional ID for MIDI learn mode. If provided, this knob can be selected for CC mapping.
 */
@Composable
fun RotaryKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    controlId: String? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    size: Dp = 64.dp,
    trackColor: Color = OrpheusColors.deepPurple,
    progressColor: Color = OrpheusColors.neonCyan,
    knobColor: Color = OrpheusColors.softPurple,
    indicatorColor: Color = OrpheusColors.neonCyan,
    labelColor: Color = progressColor,
    enabled: Boolean = true,
    valueFormatter: KnobValueFormatter? = { value ->
        ((value * 100).roundToInt() / 100.0).toString()
    },
    // Preview/render-harness seam only: forces the focus/adjust visuals without real input.
    // Every production call site leaves this null and gets live focus behavior.
    previewFocused: Boolean? = null,
    previewAdjusting: Boolean = false,
) {
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)
    val highlightMod = if (controlId != null) Modifier.highlightable(controlId) else Modifier
    val dragState = rememberKnobDragState(value, range)

    Column(
        modifier = modifier
            .then(highlightMod)
            .then(
                if (controlId != null && learnState.isActive) {
                    Modifier.learnable(controlId, learnState)
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RotaryKnobDial(
            dragState = dragState,
            onValueChange = onValueChange,
            size = size,
            range = range,
            trackColor = trackColor,
            progressColor = progressColor,
            knobColor = knobColor,
            indicatorColor = indicatorColor,
            isLearning = isLearning,
            enabled = enabled,
            previewFocused = previewFocused,
            previewAdjusting = previewAdjusting,
        )

        if (label != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = labelStyle,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        if (valueFormatter != null) {
            KnobValueText(
                dragState = dragState,
                onValueChange = onValueChange,
                range = range,
                progressColor = progressColor,
                valueFormatter = valueFormatter,
            )
        }
    }
}

// ==================== TV FOCUS PREVIEWS ====================
// previewFocused/previewAdjusting force the visuals for inspection; every real call site
// leaves them null and gets genuine D-pad/keyboard focus behavior.

@Preview(name = "TV — Focused (not adjusting)")
@Composable
private fun RotaryKnobTvFocusedPreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalTvFocusChrome provides true) {
            RotaryKnob(
                label = "ENERGY",
                value = 0.6f,
                onValueChange = {},
                progressColor = OrpheusColors.cosmicPurple,
                previewFocused = true,
            )
        }
    }
}

@Preview(name = "TV — Adjusting (pulsing glow)")
@Composable
private fun RotaryKnobTvAdjustingPreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalTvFocusChrome provides true) {
            RotaryKnob(
                label = "ENERGY",
                value = 0.6f,
                onValueChange = {},
                progressColor = OrpheusColors.cosmicPurple,
                previewFocused = true,
                previewAdjusting = true,
            )
        }
    }
}

@Preview(name = "Non-TV — Focused (unchanged ring)")
@Composable
private fun RotaryKnobNonTvFocusedPreview() {
    OrpheusTheme {
        RotaryKnob(
            label = "ENERGY",
            value = 0.6f,
            onValueChange = {},
            progressColor = OrpheusColors.cosmicPurple,
            previewFocused = true,
        )
    }
}
