package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
@Preview
fun RotaryKnobPreview() {
    OrpheusTheme {
        RotaryKnob(value = 0.5f, onValueChange = {})
    }
}

/**
 * A synth-style rotary knob control.
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
    controlId: String? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    size: Dp = 64.dp,
    trackColor: Color = OrpheusColors.deepPurple,
    progressColor: Color = OrpheusColors.neonCyan,
    knobColor: Color = OrpheusColors.softPurple,
    indicatorColor: Color = OrpheusColors.neonCyan,
    enabled: Boolean = true
) {
    // Sensitivity for drag (pixels per full range)
    val sensitivity = 200f

    // Get learn mode state from composition local
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)

    Column(
        modifier = modifier
            .then(
                if (controlId != null && learnState.isActive) {
                    Modifier.learnable(controlId, learnState)
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Track current value internally for smooth updates, syncing with external value when it changes
        var internalValue by remember(value) { mutableStateOf(value) }

        Box(
            modifier = Modifier.size(size)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(sensitivity, range, isLearning, enabled) {
                        if (isLearning || !enabled) {
                            return@pointerInput
                        }
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // Use both vertical and horizontal drag
                                val delta =
                                    (-dragAmount.y + dragAmount.x) * (range.endInclusive - range.start) / sensitivity
                                val newValue = (internalValue + delta).coerceIn(range)
                                if (newValue != internalValue) {
                                    internalValue = newValue
                                    onValueChange(newValue)
                                }
                            }
                        )
                    }
            ) {
                val strokeWidth = size.toPx() * 0.1f
                val radius = (size.toPx() - strokeWidth) / 2
                val center = Offset(size.toPx() / 2, size.toPx() / 2)

                // Track (background arc) - darker and deeper
                val startAngle = 135f
                val sweepAngle = 270f

                // Track Groove (Shadow)
                drawArc(
                    color = Color.Black.copy(alpha = 0.5f),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                drawArc(
                    color = trackColor.copy(alpha = 0.3f),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active Progress Arc with Glow
                val normalizedValue =
                    (internalValue - range.start) / (range.endInclusive - range.start)
                val currentSweep = sweepAngle * normalizedValue

                // Progress Glow
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
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round)
                )

                // Progress Core
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(progressColor.copy(alpha = 0.5f), progressColor),
                        center = center
                    ),
                    startAngle = startAngle,
                    sweepAngle = currentSweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Knob Body Calculation
                val knobRadius = radius * 0.7f
                val angleInDegrees = startAngle + currentSweep
                val angleInRadians = angleInDegrees.toDouble() * PI / 180.0

                // Knob Shadow (fake drop shadow)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                        center = center.copy(y = center.y + 4.dp.toPx()),
                        radius = knobRadius + 4.dp.toPx()
                    ),
                    radius = knobRadius + 4.dp.toPx(),
                    center = center.copy(y = center.y + 4.dp.toPx())
                )

                // Knob Main Body (Gradient for convexity)
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            OrpheusColors.softPurple.copy(alpha = 0.8f), // Highlight top-left
                            OrpheusColors.deepPurple,                    // Mid
                            Color.Black                                // Shadow bottom-right
                        ),
                        start = Offset(center.x - knobRadius, center.y - knobRadius),
                        end = Offset(center.x + knobRadius, center.y + knobRadius)
                    ),
                    radius = knobRadius,
                    center = center
                )

                // Knob Bevel/Edge
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

                // Indicator Glow Point
                drawCircle(
                    color = indicatorColor,
                    radius = strokeWidth * 0.6f,
                    center = Offset(endX, endY)
                )
            }
        }

        if (label != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = ((internalValue * 100).roundToInt() / 100.0).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = progressColor,
            textAlign = TextAlign.Center
        )
    }
}
