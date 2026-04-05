package org.balch.orpheus.ui.widgets

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared state holder for knob drag interaction. */
internal class KnobDragState(
    initialValue: Float,
    private val range: ClosedFloatingPointRange<Float>,
    private val sensitivity: Float,
) {
    var internalValue by mutableStateOf(initialValue)

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
) {
    val sensitivity = 200f
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    Box(modifier = modifier.size(size)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
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
    }
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
