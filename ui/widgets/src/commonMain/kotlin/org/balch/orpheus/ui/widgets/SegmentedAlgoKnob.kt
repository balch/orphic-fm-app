package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.darken
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun SegmentedAlgoKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    controlId: String? = null
) {
    val sensitivity = 150f
    var internalValue by remember(value) { mutableStateOf(value) }

    val algoColors = listOf(
        Color(0xFF69BE28), // Green - Xfade
        Color(0xFFFDB927), // Yellow - Fold
        Color(0xFFFF9F00), // Orange - Analog RM
        Color(0xFFFF5252), // Red - Digital RM
        Color(0xFFFF00FF), // Magenta - XOR
        Color(0xFFBA68C8), // Purple - Compare
        Color(0xFF4FC3F7), // Blue - Vocoder
        Color(0xFF29B6F6), // Blue - Vocoder (Longer Release)
        Color(0xFFE1F5FE)  // Pale Blue - Freeze
    )

    val algoNames = listOf(
        "CROSSFADE",
        "WAVEFOLDER",
        "ANALOG RM",
        "DIGITAL RM",
        "XOR",
        "COMPARE",
        "VOCODER",
        "VOCODER+",
        "FREEZE"
    )

    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)

    val activeIdx = (internalValue * (algoNames.size - 1)).roundToInt().coerceIn(0, algoNames.size - 1)

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
        Text(
            text = algoNames[activeIdx],
            style = MaterialTheme.typography.labelMedium,
            color = algoColors[activeIdx],
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(modifier = Modifier.size(size)) {
            Canvas(
                modifier = Modifier
                    .size(size)
                    .pointerInput(Unit) {
                        if (isLearning) return@pointerInput
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = (-dragAmount) / sensitivity
                            val newValue = (internalValue + delta).coerceIn(0f, 1f)
                            if (newValue != internalValue) {
                                internalValue = newValue
                                onValueChange(newValue)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        if (isLearning) return@pointerInput
                        detectTapGestures { offset ->
                            val dx = offset.x - size.toPx() / 2
                            val dy = offset.y - size.toPx() / 2
                            val dist = sqrt(dx * dx + dy * dy)
                            val radiusPx = size.toPx() / 2
                            
                            // If it's on the ring (outside center knob)
                            if (dist > radiusPx * 0.55f && dist < radiusPx * 1.05f) {
                                var angle = atan2(dy, dx) * 180f / PI.toFloat()
                                if (angle < 0) angle += 360f
                                
                                // Segments start at 135 and sweep 270
                                var relAngle = angle - 135f
                                while (relAngle < 0) relAngle += 360f
                                
                                if (relAngle <= 285f) { // Allow slight overshoot
                                    val totalSweep = 270f
                                    val nextValue = (relAngle / totalSweep).coerceIn(0f, 1f)
                                    internalValue = nextValue
                                    onValueChange(nextValue)
                                    return@detectTapGestures
                                }
                            }
                            
                            // Otherwise step to next (original center tap behavior)
                            val segmentCount = 9
                            val currentIdx = (internalValue * (segmentCount - 1)).roundToInt()
                            val nextIdx = (currentIdx + 1) % segmentCount
                            val nextValue = nextIdx.toFloat() / (segmentCount - 1)
                            internalValue = nextValue
                            onValueChange(nextValue)
                        }
                    }
            ) {
                val center = Offset(size.toPx() / 2, size.toPx() / 2)
                val radius = size.toPx() / 2
                val innerRadius = radius * 0.65f
                val strokeWidth = 10.dp.toPx()

                // Draw segments background
                val segmentCount = 9
                val startAngle = 135f
                val totalSweep = 270f
                val segmentSweep = totalSweep / (segmentCount - 1)

                // Outer Ring shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth)
                )

                for (i in 0 until segmentCount) {
                    val angle = startAngle + i * segmentSweep
                    val rad = (angle * PI / 180f).toFloat()
                    
                    val targetAlgo = internalValue * (segmentCount - 1)
                    val distance = abs(i - targetAlgo)
                    val intensity = (1f - distance * 1.5f).coerceIn(0.1f, 1f)
                    
                    val color = algoColors[i]
                    
                    // Segment Light
                    drawArc(
                        color = color.darken(.45f).copy(alpha = 0.2f + .8f * intensity),
                        startAngle = angle - 12f,
                        sweepAngle = 24f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius + strokeWidth/2, center.y - radius + strokeWidth/2),
                        size = Size(radius * 2 - strokeWidth, radius * 2 - strokeWidth),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                    
                    // Drawing Icons at each segment position
                    val iconRadius = radius * 0.9f
                    val iconX = center.x + iconRadius * cos(rad)
                    val iconY = center.y + iconRadius * sin(rad)
                    val iconSize = 12.dp.toPx()
                    
                    withTransform({
                        translate(iconX, iconY)
                    }) {
                        drawIcon(i, color.lighten(), iconSize)
                    }
                }

                // Draw Knob
                val currentAngle = startAngle + internalValue * totalSweep
                val currentRad = (currentAngle * PI / 180f).toFloat()
                
                // Knob Shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = innerRadius + 4.dp.toPx(),
                    center = center.copy(y = center.y + 4.dp.toPx())
                )
                
                // Get active algorithm color for knob tinting
                val activeIdx = (internalValue * (segmentCount - 1)).toInt().coerceIn(0, segmentCount - 1)
                val activeColor = algoColors[activeIdx]

                // Create smooth gradient with algorithm color sync
                val shadowColor = OrpheusColors.almostBlack

                // Knob Body with multi-stop gradient
                drawCircle(
                    brush = Brush.radialGradient(
                        0.2f to activeColor.darken().copy(alpha = 0.3f),
                        .5f to activeColor.darken().copy(alpha = 0.6f),
                        0.85f to shadowColor,
                        1.0f to Color.Transparent,
                        center = center,
                        radius = innerRadius,
                        tileMode = TileMode.Clamp,
                    ),
                    radius = innerRadius,
                    center = center,
                    blendMode = BlendMode.Plus
                )

                // Knob Indicator with active color
                val indicatorLength = innerRadius * 0.7f
                val endX = center.x + indicatorLength * cos(currentRad)
                val endY = center.y + indicatorLength * sin(currentRad)

                drawLine(
                    color = activeColor.copy(alpha = 0.95f),
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Central status LED
                
                drawCircle(
                    color = activeColor,
                    radius = innerRadius * 0.15f,
                    center = center
                )
                drawCircle( // Glow
                    color = activeColor.copy(alpha = 0.7f),
                    radius = innerRadius * 0.25f,
                    center = center
                )
                drawCircle( // Glow
                    color = activeColor.copy(alpha = 0.4f),
                    radius = innerRadius * 0.55f,
                    center = center
                )
                drawCircle( // Glow
                    color = activeColor.copy(alpha = 0.2f),
                    radius = innerRadius * 0.85f,
                    center = center
                )
            }
        }
    }
}

private fun DrawScope.drawIcon(index: Int, color: Color, size: Float) {
    val s = size / 2
    when (index) {
        0 -> { // XFade: Overlapping circles
            drawCircle(color, radius = s * 0.6f, center = Offset(-s * 0.3f, 0f), style = Stroke(2f))
            drawCircle(color, radius = s * 0.6f, center = Offset(s * 0.3f, 0f), style = Stroke(2f))
        }
        1 -> { // Fold: Wave
            val path = Path().apply {
                moveTo(-s, s)
                lineTo(-s/2, -s)
                lineTo(0f, s)
                lineTo(s/2, -s)
                lineTo(s, s)
            }
            drawPath(path, color, style = Stroke(2f))
        }
        2 -> { // Diode RM: Rhombus
            val path = Path().apply {
                moveTo(0f, -s)
                lineTo(s, 0f)
                lineTo(0f, s)
                lineTo(-s, 0f)
                close()
            }
            drawPath(path, color, style = Stroke(2f))
        }
        3 -> { // Digital RM: Cross
            drawLine(color, Offset(-s, -s), Offset(s, s), 2f)
            drawLine(color, Offset(s, -s), Offset(-s, s), 2f)
            drawLine(color, Offset(0f, -s), Offset(0f, s), 2f)
        }
        4 -> { // XOR: Logic shape (approx)
            val path = Path().apply {
                moveTo(-s, -s)
                quadraticTo(0f, 0f, -s, s)
                moveTo(-s*0.5f, -s)
                quadraticTo(s*0.5f, 0f, -s*0.5f, s)
                lineTo(s, 0f)
                close()
            }
            drawPath(path, color, style = Stroke(2f))
        }
        5 -> { // Compare: Square pulses
            val path = Path().apply {
                moveTo(-s, s)
                lineTo(-s, 0f)
                lineTo(0f, 0f)
                lineTo(0f, -s)
                lineTo(s, -s)
                lineTo(s, s)
            }
            drawPath(path, color, style = Stroke(2f))
        }
        6 -> { // Vocoder: Spectrum bars
            for (i in -2..2) {
                val h = s * (1f - abs(i) * 0.3f)
                drawLine(color, Offset(i * s * 0.4f, h), Offset(i * s * 0.4f, -h), 3f)
            }
        }
        7 -> { // Vocoder+: Spectrum with decay indiciation (inverted V shape overlay?)
            for (i in -2..2) {
                val h = s * (1f - abs(i) * 0.1f) // Fuller spectrum
                drawLine(color, Offset(i * s * 0.4f, h), Offset(i * s * 0.4f, -h), 3f)
            }
        }
        8 -> { // Freeze: Snowflake / Hold
             // Horizontal bars for "hold"
             drawLine(color, Offset(-s * 0.8f, -s * 0.3f), Offset(s * 0.8f, -s * 0.3f), 4f)
             drawLine(color, Offset(-s * 0.8f, s * 0.3f), Offset(s * 0.8f, s * 0.3f), 4f)
             // Vertical connectors
             drawLine(color, Offset(-s * 0.4f, -s * 0.5f), Offset(-s * 0.4f, s * 0.5f), 2f)
             drawLine(color, Offset(s * 0.4f, -s * 0.5f), Offset(s * 0.4f, s * 0.5f), 2f)
        }
    }
}

@Preview
@Composable
fun SegmentedAlgoKnobPreview() {
    OrpheusTheme {
        Box(modifier = Modifier.background(OrpheusColors.panelBackground).padding(40.dp)) {
            SegmentedAlgoKnob(
                value = 0.5f,
                onValueChange = {}
            )
        }
    }
}
