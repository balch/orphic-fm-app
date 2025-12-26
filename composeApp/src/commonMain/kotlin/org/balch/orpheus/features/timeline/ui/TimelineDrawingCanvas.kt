package org.balch.orpheus.features.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.timeline.TimelinePath
import org.balch.orpheus.features.timeline.TimelinePoint
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Canvas for drawing timeline automation paths.
 *
 * Touch gestures:
 * - When first touching an empty path: draws horizontal line from start to touch point
 * - Moving forward (left to right): adds points to the path
 * - Moving backward (right to left): removes points after the touch position
 * - Lifting finger: draws horizontal line from last point to end (completes path)
 *
 * @param path The current timeline path to display
 * @param currentPosition Current playhead position (0.0 to 1.0)
 * @param color Color for the path line
 * @param onPathStarted Called when drawing starts on an empty path
 * @param onPointAdded Called when a new point is added
 * @param onPointsRemovedAfter Called when points after a position should be removed
 * @param onPathCompleted Called when drawing ends and path should be completed
 * @param enabled Whether drawing is enabled
 */
@Composable
fun TimelineDrawingCanvas(
    path: TimelinePath,
    currentPosition: Float,
    color: Color,
    onPathStarted: (TimelinePoint) -> Unit,
    onPointAdded: (TimelinePoint) -> Unit,
    onPointsRemovedAfter: (Float) -> Unit,
    onPathCompleted: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)

    var lastDrawnTime by remember { mutableStateOf(-1f) }
    var isDrawing by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF0A0A12))
            .border(1.dp, color.copy(alpha = 0.3f), shape)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, path.isComplete) {
                    if (!enabled || path.isComplete) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            isDrawing = true
                            val time = (offset.x / size.width).coerceIn(0f, 1f)
                            val value = 1f - (offset.y / size.height).coerceIn(0f, 1f)

                            if (path.points.isEmpty()) {
                                // Starting fresh - draw line from start to touch
                                onPathStarted(TimelinePoint(time, value))
                            } else {
                                // Already have points - add or remove based on position
                                val lastTime = path.points.lastOrNull()?.time ?: 0f
                                if (time < lastTime) {
                                    onPointsRemovedAfter(time)
                                }
                                onPointAdded(TimelinePoint(time, value))
                            }
                            lastDrawnTime = time
                        },
                        onDrag = { change, _ ->
                            val time = (change.position.x / size.width).coerceIn(0f, 1f)
                            val value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)

                            if (time < lastDrawnTime) {
                                // Moving backward - remove points
                                onPointsRemovedAfter(time)
                            }
                            onPointAdded(TimelinePoint(time, value))
                            lastDrawnTime = time
                            change.consume()
                        },
                        onDragEnd = {
                            isDrawing = false
                            // Complete the path by extending to end
                            val lastValue = path.points.lastOrNull()?.value ?: 0.5f
                            onPathCompleted(lastValue)
                            lastDrawnTime = -1f
                        },
                        onDragCancel = {
                            isDrawing = false
                            lastDrawnTime = -1f
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height

            // Draw grid lines (subtle)
            val gridColor = Color.White.copy(alpha = 0.05f)
            for (i in 1..3) {
                val y = height * i / 4
                drawLine(gridColor, Offset(0f, y), Offset(width, y))
            }
            for (i in 1..9) {
                val x = width * i / 10
                drawLine(gridColor, Offset(x, 0f), Offset(x, height))
            }

            // Draw the path
            if (path.points.isNotEmpty()) {
                val pathDraw = Path()
                var first = true

                for (point in path.points) {
                    val x = point.time * width
                    val y = (1f - point.value) * height

                    if (first) {
                        pathDraw.moveTo(x, y)
                        first = false
                    } else {
                        pathDraw.lineTo(x, y)
                    }
                }

                drawPath(
                    path = pathDraw,
                    color = color,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw points as small circles
                for (point in path.points) {
                    val x = point.time * width
                    val y = (1f - point.value) * height
                    drawCircle(
                        color = color,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            // Draw playhead (vertical line at current position)
            val playheadX = currentPosition * width
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 2.dp.toPx()
            )

            // Draw playhead position indicator
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(playheadX, height / 2)
            )
        }
    }
}

@Preview
@Composable
private fun TimelineDrawingCanvasEmptyPreview() {
    TimelineDrawingCanvas(
        path = TimelinePath(),
        currentPosition = 0.3f,
        color = OrpheusColors.neonCyan,
        onPathStarted = {},
        onPointAdded = {},
        onPointsRemovedAfter = {},
        onPathCompleted = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    )
}

@Preview
@Composable
private fun TimelineDrawingCanvasWithPathPreview() {
    val samplePath = TimelinePath(
        points = listOf(
            TimelinePoint(0f, 0.5f),
            TimelinePoint(0.2f, 0.7f),
            TimelinePoint(0.4f, 0.3f),
            TimelinePoint(0.6f, 0.8f),
            TimelinePoint(0.8f, 0.4f),
            TimelinePoint(1f, 0.6f)
        ),
        isComplete = true
    )

    TimelineDrawingCanvas(
        path = samplePath,
        currentPosition = 0.5f,
        color = OrpheusColors.neonMagenta,
        onPathStarted = {},
        onPointAdded = {},
        onPointsRemovedAfter = {},
        onPathCompleted = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    )
}
