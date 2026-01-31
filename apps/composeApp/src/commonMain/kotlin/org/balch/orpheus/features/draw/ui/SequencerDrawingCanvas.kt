package org.balch.orpheus.features.draw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.draw.DrawSequencerParameter
import org.balch.orpheus.features.draw.SequencerPath
import org.balch.orpheus.features.draw.SequencerPoint
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Canvas for drawing sequencer automation paths.
 *
 * Touch gestures:
 * - When first touching an empty path: draws horizontal line from start to touch point
 * - Moving forward (left to right): adds points to the path
 * - Moving backward (right to left): removes points after the touch position
 * - Lifting finger: draws horizontal line from last point to end (completes path)
 *
 * @param paths Map of all sequencer paths to display
 * @param activeParameter The currently selected parameter for drawing
 * @param currentPosition Current playhead position (0.0 to 1.0)
 * @param onPathStarted Called when drawing starts on an empty path
 * @param onPointAdded Called when a new point is added
 * @param onPointsRemovedAfter Called when points after a position should be removed
 * @param onPathCompleted Called when drawing ends and path should be completed
 * @param enabled Whether drawing is enabled
 */
@Composable
fun SequencerDrawingCanvas(
    paths: Map<DrawSequencerParameter, SequencerPath>,
    activeParameter: DrawSequencerParameter?,
    currentPosition: Float,
    onPathStarted: (SequencerPoint) -> Unit,
    onPointAdded: (SequencerPoint) -> Unit,
    onPointsRemovedAfter: (Float) -> Unit,
    onPathCompleted: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)

    var lastDrawnTime by remember { mutableStateOf(-1f) }
    var lastDrawnValue by remember { mutableStateOf(0.5f) }
    var isDrawing by remember { mutableStateOf(false) }
    
    // Determine active path/color for interaction
    val activePath = if (activeParameter != null) paths[activeParameter] ?: SequencerPath() else SequencerPath()
    val activeColor = activeParameter?.color ?: Color.White

    // Main Canvas Box with labels inside
    Box(
        modifier = modifier
            .clip(shape)
            .background(OrpheusColors.deepSpaceDark)
            .border(1.dp, activeColor.copy(alpha = 0.3f), shape)
    ) {
        // Y-Axis Labels inside the box (Max at top, Min at bottom)
        Text(
            text = "Max",
            fontSize = 8.sp,
            color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 2.dp)
        )
        Text(
            text = "Min",
            fontSize = 8.sp,
            color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 4.dp, bottom = 2.dp)
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, activeParameter, activePath.isComplete) {
                    if (!enabled || activeParameter == null || activePath.isComplete) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            isDrawing = true
                            val time = (offset.x / size.width).coerceIn(0f, 1f)
                            val value = 1f - (offset.y / size.height).coerceIn(0f, 1f)

                            if (activePath.points.isEmpty()) {
                                // Starting fresh - draw line from start to touch
                                onPathStarted(SequencerPoint(time, value))
                            } else {
                                // Already have points - add or remove based on position
                                val lastTime = activePath.points.lastOrNull()?.time ?: 0f
                                if (time < lastTime) {
                                    onPointsRemovedAfter(time)
                                }
                                onPointAdded(SequencerPoint(time, value))
                            }
                            lastDrawnTime = time
                            lastDrawnValue = value
                        },
                        onDrag = { change, _ ->
                            val time = (change.position.x / size.width).coerceIn(0f, 1f)
                            val value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)

                            if (time < lastDrawnTime) {
                                // Moving backward - remove points
                                onPointsRemovedAfter(time)
                            }
                            onPointAdded(SequencerPoint(time, value))
                            lastDrawnTime = time
                            lastDrawnValue = value
                            change.consume()
                        },
                        onDragEnd = {
                            isDrawing = false
                            // Complete the path by extending to end with the LAST drawn value
                            onPathCompleted(lastDrawnValue)
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

            // Helper to draw a path
            fun drawSequencerPath(sequencerPath: SequencerPath, color: Color, isActive: Boolean) {
                if (sequencerPath.points.isEmpty()) return
                
                val pathDraw = Path()
                
                if (sequencerPath.points.size > 1) {
                    val firstPoint = sequencerPath.points.first()
                    pathDraw.moveTo(firstPoint.time * width, (1f - firstPoint.value) * height)
                    
                    for (i in 0 until sequencerPath.points.size - 1) {
                        val p0 = sequencerPath.points[i]
                        val p1 = sequencerPath.points[i + 1]
                        
                        val x0 = p0.time * width
                        val y0 = (1f - p0.value) * height
                        val x1 = p1.time * width
                        val y1 = (1f - p1.value) * height
                        
                        // Cubic curve control points for smoothing
                        val controlDist = (x1 - x0) / 2f
                        
                        pathDraw.cubicTo(
                            x0 + controlDist, y0,
                            x1 - controlDist, y1,
                            x1, y1
                        )
                    }
                } else {
                    val p = sequencerPath.points.first()
                    pathDraw.moveTo(p.time * width, (1f - p.value) * height)
                    pathDraw.lineTo(p.time * width, (1f - p.value) * height) // Just a dot
                }

                drawPath(
                    path = pathDraw,
                    color = color.copy(alpha = if (isActive) 1f else 0.5f), // Dim inactive
                    style = Stroke(
                        width = if (isActive) 3.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw points only for active path
                if (isActive) {
                    for (point in sequencerPath.points) {
                        val x = point.time * width
                        val y = (1f - point.value) * height
                        drawCircle(
                            color = color,
                            radius = 3.dp.toPx(), // Slightly smaller dots
                            center = Offset(x, y)
                        )
                    }
                }
            }

            // 1. Draw Inactive Paths first
            paths.forEach { (param, path) ->
                if (param != activeParameter) {
                    drawSequencerPath(path, param.color, isActive = false)
                }
            }

            // 2. Draw Active Path last (on top)
            if (activeParameter != null) {
                drawSequencerPath(activePath, activeColor, isActive = true)
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
            if (activeParameter != null && activePath.points.isNotEmpty()) {
                // Find interpolated value at current playhead position for active param
                // Simple linear check for finding closest segment
                // In a real implementation this would likely be more robust
                // For now just draw on the line
                drawCircle(
                     color = Color.White,
                     radius = 5.dp.toPx(),
                     center = Offset(playheadX, height / 2) // Just vertically centered for now
                )
            }
        }
    }
}


