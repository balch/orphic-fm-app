package org.balch.orpheus.features.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.timeline.TimelinePath
import org.balch.orpheus.features.timeline.TimelinePoint
import org.balch.orpheus.features.timeline.TweakTimelineConfig
import org.balch.orpheus.features.timeline.TweakTimelineParameter
import org.balch.orpheus.features.timeline.TweakTimelineState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact inline view for multi-parameter timeline automation.
 *
 * Shows:
 * - Play/Pause toggle button
 * - Stop button
 * - Time remaining display
 * - Mini timeline preview with all paths (click to expand)
 * - Clickable legend for parameter identification
 *
 * @param state Current timeline state with all paths
 * @param onPlayPause Called when play/pause is toggled
 * @param onStop Called when stop is pressed
 * @param onExpand Called when mini preview is tapped
 */
@Composable
fun CompactTweakTimelineView(
    state: TweakTimelineState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val isActive = state.config.enabled
    val accentColor = OrpheusColors.neonCyan
    var showLegend by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .fillMaxSize()
                .background(Color(0xFF1A1A2A).copy(alpha = 0.8f))
                .border(1.dp, accentColor.copy(alpha = if (isActive) 0.5f else 0.2f), shape)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Play/Pause button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isActive) accentColor.copy(alpha = 0.3f) else Color(0xFF2A2A3A))
                    .border(1.dp, accentColor.copy(alpha = 0.5f), CircleShape)
                    .clickable(enabled = isActive) { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.isPlaying) "⏸" else "▶",
                    fontSize = 14.sp,
                    color = if (isActive) accentColor else accentColor.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center
                )
            }

            // Stop button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A3A))
                    .border(1.dp, accentColor.copy(alpha = 0.3f), CircleShape)
                    .clickable(enabled = isActive && (state.isPlaying || state.currentPosition > 0f)) { onStop() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⏹",
                    fontSize = 12.sp,
                    color = if (isActive) accentColor.copy(alpha = 0.7f) else accentColor.copy(alpha = 0.3f),
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
                    text = "${state.config.selectedParameters.size} params",
                    fontSize = 8.sp,
                    color = accentColor.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Mini timeline preview (tap to expand)
            MultiPathTimelinePreview(
                paths = state.paths,
                currentPosition = state.currentPosition,
                enabled = isActive,
                onClick = onExpand,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            )

            // Legend toggle button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (showLegend) accentColor.copy(alpha = 0.3f) else Color(0xFF2A2A3A))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), CircleShape)
                    .clickable { showLegend = !showLegend },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showLegend) accentColor else accentColor.copy(alpha = 0.6f)
                )
            }
        }

        // Collapsible legend
        if (showLegend && state.config.selectedParameters.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            ParameterLegend(
                parameters = state.config.selectedParameters,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Miniature timeline preview showing all paths with their colors.
 */
@Composable
private fun MultiPathTimelinePreview(
    paths: Map<TweakTimelineParameter, TimelinePath>,
    currentPosition: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(Color(0xFF0A0A12))
            .border(1.dp, OrpheusColors.neonCyan.copy(alpha = if (enabled) 0.3f else 0.1f), shape)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Draw each path with its parameter's color
            paths.forEach { (param, path) ->
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
                        color = param.color.copy(alpha = if (enabled) param.color.alpha else param.color.alpha * 0.5f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Draw playhead
            val playheadX = currentPosition * width
            drawLine(
                color = Color.White.copy(alpha = if (enabled) 0.8f else 0.3f),
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/**
 * Clickable legend showing parameter names and colors.
 */
@Composable
private fun ParameterLegend(
    parameters: List<TweakTimelineParameter>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A).copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parameters.forEach { param ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(param.color)
                )
                Text(
                    text = param.label,
                    fontSize = 9.sp,
                    color = param.color,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Preview(heightDp = 240)
@Composable
private fun CompactTweakTimelineViewPreview() {
    val samplePath = TimelinePath(
        points = listOf(
            TimelinePoint(0f, 0.5f),
            TimelinePoint(0.3f, 0.8f),
            TimelinePoint(0.6f, 0.2f),
            TimelinePoint(1f, 0.6f)
        ),
        isComplete = true
    )

    CompactTweakTimelineView(
        state = TweakTimelineState(
            config = TweakTimelineConfig(
                enabled = true,
                selectedParameters = listOf(
                    TweakTimelineParameter.LFO_FREQ_A,
                    TweakTimelineParameter.DELAY_TIME_1,
                    TweakTimelineParameter.DIST_DRIVE
                )
            ),
            paths = mapOf(
                TweakTimelineParameter.LFO_FREQ_A to samplePath,
                TweakTimelineParameter.DELAY_TIME_1 to TimelinePath(
                    points = listOf(TimelinePoint(0f, 0.2f), TimelinePoint(1f, 0.9f)),
                    isComplete = true
                ),
                TweakTimelineParameter.DIST_DRIVE to TimelinePath()
            ),
            currentPosition = 0.4f,
            isPlaying = true
        ),
        onPlayPause = {},
        onStop = {},
        onExpand = {},
        modifier = Modifier.fillMaxWidth()
    )
}

@Preview(heightDp = 240)
@Composable
private fun CompactTweakTimelineViewDisabledPreview() {
    CompactTweakTimelineView(
        state = TweakTimelineState(
            config = TweakTimelineConfig(enabled = false),
            paths = emptyMap(),
            currentPosition = 0f,
            isPlaying = false
        ),
        onPlayPause = {},
        onStop = {},
        onExpand = {},
        modifier = Modifier.fillMaxWidth()
    )
}
