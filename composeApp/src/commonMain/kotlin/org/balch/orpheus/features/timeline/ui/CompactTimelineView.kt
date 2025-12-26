package org.balch.orpheus.features.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.timeline.DuoTimelineConfig
import org.balch.orpheus.features.timeline.TimelineInstanceState
import org.balch.orpheus.features.timeline.TimelinePath
import org.balch.orpheus.features.timeline.TimelinePoint
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact inline view for timeline automation.
 *
 * Shows:
 * - Play/Pause toggle button
 * - Stop button
 * - Time remaining display
 * - Mini timeline preview (tap to expand)
 *
 * @param state Current timeline instance state
 * @param color Accent color for this timeline
 * @param onPlayPause Called when play/pause is toggled
 * @param onStop Called when stop is pressed
 * @param onExpand Called when mini preview is tapped
 */
@Composable
fun CompactTimelineView(
    state: TimelineInstanceState,
    color: Color,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val isActive = state.config.enabled

    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF1A1A2A).copy(alpha = 0.8f))
            .border(1.dp, color.copy(alpha = if (isActive) 0.5f else 0.2f), shape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Play/Pause button
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isActive) color.copy(alpha = 0.3f) else Color(0xFF2A2A3A))
                .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
                .clickable(enabled = isActive) { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state.isPlaying) "⏸" else "▶",
                fontSize = 14.sp,
                color = if (isActive) color else color.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }

        // Stop button
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF2A2A3A))
                .border(1.dp, color.copy(alpha = 0.3f), CircleShape)
                .clickable(enabled = isActive && (state.isPlaying || state.currentPosition > 0f)) { onStop() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⏹",
                fontSize = 12.sp,
                color = if (isActive) color.copy(alpha = 0.7f) else color.copy(alpha = 0.3f),
                textAlign = TextAlign.Center
            )
        }

        // Time display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            val remainingSeconds = ((1f - state.currentPosition) * state.config.durationSeconds).toInt()
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60

            Text(
                text = "$minutes:${seconds.toString().padStart(2, '0')}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = state.config.parameterName,
                fontSize = 8.sp,
                color = color.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Mini timeline preview (tap to expand)
        MiniTimelinePreview(
            pathA = state.pathA,
            pathB = state.pathB,
            currentPosition = state.currentPosition,
            color = color,
            enabled = isActive,
            onClick = onExpand,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        )
    }
}

/**
 * Miniature timeline preview showing both paths.
 */
@Composable
private fun MiniTimelinePreview(
    pathA: TimelinePath,
    pathB: TimelinePath,
    currentPosition: Float,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF0A0A12))
            .border(1.dp, color.copy(alpha = if (enabled) 0.3f else 0.1f), shape)
            .clickable(enabled = enabled) { onClick() }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            val width = size.width
            val height = size.height

            // Draw path A (top half)
            if (pathA.points.isNotEmpty()) {
                val pathDraw = androidx.compose.ui.graphics.Path()
                var first = true
                for (point in pathA.points) {
                    val x = point.time * width
                    val y = (1f - point.value) * (height / 2)
                    if (first) {
                        pathDraw.moveTo(x, y)
                        first = false
                    } else {
                        pathDraw.lineTo(x, y)
                    }
                }
                drawPath(
                    path = pathDraw,
                    color = color.copy(alpha = if (enabled) 0.8f else 0.4f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
            }

            // Draw path B (bottom half)
            if (pathB.points.isNotEmpty()) {
                val pathDraw = androidx.compose.ui.graphics.Path()
                var first = true
                for (point in pathB.points) {
                    val x = point.time * width
                    val y = height / 2 + (1f - point.value) * (height / 2)
                    if (first) {
                        pathDraw.moveTo(x, y)
                        first = false
                    } else {
                        pathDraw.lineTo(x, y)
                    }
                }
                drawPath(
                    path = pathDraw,
                    color = color.copy(alpha = if (enabled) 0.6f else 0.3f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
            }

            // Draw playhead
            val playheadX = currentPosition * width
            drawLine(
                color = Color.White.copy(alpha = if (enabled) 0.8f else 0.3f),
                start = androidx.compose.ui.geometry.Offset(playheadX, 0f),
                end = androidx.compose.ui.geometry.Offset(playheadX, height),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Preview
@Composable
private fun CompactTimelineViewPreview() {
    val samplePath = TimelinePath(
        points = listOf(
            TimelinePoint(0f, 0.5f),
            TimelinePoint(0.3f, 0.8f),
            TimelinePoint(0.6f, 0.2f),
            TimelinePoint(1f, 0.6f)
        ),
        isComplete = true
    )

    CompactTimelineView(
        state = TimelineInstanceState(
            config = DuoTimelineConfig.LFO.copy(enabled = true),
            pathA = samplePath,
            pathB = TimelinePath(),
            currentPosition = 0.4f,
            isPlaying = true
        ),
        color = OrpheusColors.neonCyan,
        onPlayPause = {},
        onStop = {},
        onExpand = {},
        modifier = Modifier.fillMaxWidth()
    )
}

@Preview
@Composable
private fun CompactTimelineViewDisabledPreview() {
    CompactTimelineView(
        state = TimelineInstanceState(
            config = DuoTimelineConfig.LFO.copy(enabled = false),
            pathA = TimelinePath(),
            pathB = TimelinePath(),
            currentPosition = 0f,
            isPlaying = false
        ),
        color = OrpheusColors.neonCyan,
        onPlayPause = {},
        onStop = {},
        onExpand = {},
        modifier = Modifier.fillMaxWidth()
    )
}
