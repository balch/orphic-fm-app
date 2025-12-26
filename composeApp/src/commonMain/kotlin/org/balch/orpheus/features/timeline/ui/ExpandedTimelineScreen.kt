package org.balch.orpheus.features.timeline.ui

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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import org.balch.orpheus.features.timeline.PlaybackMode
import org.balch.orpheus.features.timeline.TimelineInstanceState
import org.balch.orpheus.features.timeline.TimelinePath
import org.balch.orpheus.features.timeline.TimelinePoint
import org.balch.orpheus.features.timeline.TimelineTarget
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Full-screen expanded editor for timeline automation.
 *
 * Layout:
 * - Header: Title, enable toggle, cancel/save buttons
 * - Controls: Duration slider, playback mode toggles
 * - Drawing: Two canvases (or one with param switcher on mobile)
 * - Parameter labels and clear buttons
 */
@Composable
fun ExpandedTimelineScreen(
    target: TimelineTarget,
    state: TimelineInstanceState,
    selectedParam: Int,
    color: Color,
    onDurationChange: (Float) -> Unit,
    onPlaybackModeChange: (PlaybackMode) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onParamSelect: (Int) -> Unit,
    onPathStarted: (Int, TimelinePoint) -> Unit,
    onPointAdded: (Int, TimelinePoint) -> Unit,
    onPointsRemovedAfter: (Int, Float) -> Unit,
    onPathCompleted: (Int, Float) -> Unit,
    onClearPath: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF12121A))
            .border(2.dp, color.copy(alpha = 0.4f), shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══════════════════════════════════════════════════════════
        // HEADER
        // ═══════════════════════════════════════════════════════════
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A2A2A))
                    .border(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.5f), CircleShape)
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center
                )
            }

            // Title + Enable toggle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${state.config.parameterName} Timeline",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Enable toggle (3-way style like LFO)
                EnableToggle(
                    enabled = state.config.enabled,
                    onEnabledChange = onEnabledChange,
                    color = color
                )
            }

            // Save button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A3A2A))
                    .border(1.dp, Color(0xFF6BFF6B).copy(alpha = 0.5f), CircleShape)
                    .clickable { onSave() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6BFF6B),
                    textAlign = TextAlign.Center
                )
            }
        }

        // ═══════════════════════════════════════════════════════════
        // CONTROLS
        // ═══════════════════════════════════════════════════════════
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Duration slider
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Duration: ${state.config.durationSeconds.toInt()}s",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Slider(
                    value = state.config.durationSeconds,
                    onValueChange = onDurationChange,
                    valueRange = DuoTimelineConfig.MIN_DURATION..DuoTimelineConfig.MAX_DURATION,
                    colors = SliderDefaults.colors(
                        thumbColor = color,
                        activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Playback mode toggles
            PlaybackModeSelector(
                mode = state.config.playbackMode,
                onModeChange = onPlaybackModeChange,
                color = color
            )
        }

        // ═══════════════════════════════════════════════════════════
        // PARAMETER SELECTOR (for mobile - show one canvas at a time)
        // ═══════════════════════════════════════════════════════════
        ParamSelector(
            paramALabel = state.config.paramALabel,
            paramBLabel = state.config.paramBLabel,
            selectedParam = selectedParam,
            onSelect = onParamSelect,
            color = color
        )

        // ═══════════════════════════════════════════════════════════
        // DRAWING CANVAS
        // ═══════════════════════════════════════════════════════════
        val currentPath = if (selectedParam == 0) state.pathA else state.pathB
        val paramLabel = if (selectedParam == 0) state.config.paramALabel else state.config.paramBLabel

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Canvas header with label and clear button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = paramLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2A2A3A))
                        .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .clickable(enabled = currentPath.points.isNotEmpty()) {
                            onClearPath(selectedParam)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "⌫",
                            fontSize = 12.sp,
                            color = if (currentPath.points.isNotEmpty()) color else color.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "Clear",
                            fontSize = 10.sp,
                            color = if (currentPath.points.isNotEmpty()) color else color.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Drawing canvas
            TimelineDrawingCanvas(
                path = currentPath,
                currentPosition = state.currentPosition,
                color = color,
                onPathStarted = { onPathStarted(selectedParam, it) },
                onPointAdded = { onPointAdded(selectedParam, it) },
                onPointsRemovedAfter = { onPointsRemovedAfter(selectedParam, it) },
                onPathCompleted = { onPathCompleted(selectedParam, it) },
                enabled = state.config.enabled && !currentPath.isComplete,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EnableToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("OFF" to false, "ON" to true).forEach { (label, value) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (enabled == value) color.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { onEnabledChange(value) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = if (enabled == value) FontWeight.Bold else FontWeight.Normal,
                    color = if (enabled == value) color else color.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun PlaybackModeSelector(
    mode: PlaybackMode,
    onModeChange: (PlaybackMode) -> Unit,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PlaybackMode.entries.forEach { m ->
            val label = when (m) {
                PlaybackMode.ONCE -> "→"
                PlaybackMode.LOOP -> "⟳"
                PlaybackMode.PING_PONG -> "↔"
            }
            val tooltip = when (m) {
                PlaybackMode.ONCE -> "Once"
                PlaybackMode.LOOP -> "Loop"
                PlaybackMode.PING_PONG -> "Ping-Pong"
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (mode == m) color.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { onModeChange(m) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Normal,
                    color = if (mode == m) color else color.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ParamSelector(
    paramALabel: String,
    paramBLabel: String,
    selectedParam: Int,
    onSelect: (Int) -> Unit,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(paramALabel to 0, paramBLabel to 1).forEach { (label, index) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selectedParam == index) color.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selectedParam == index) FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedParam == index) color else color.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Preview
@Composable
private fun ExpandedTimelineScreenPreview() {
    val samplePath = TimelinePath(
        points = listOf(
            TimelinePoint(0f, 0.5f),
            TimelinePoint(0.3f, 0.8f),
            TimelinePoint(0.6f, 0.2f),
            TimelinePoint(1f, 0.6f)
        ),
        isComplete = true
    )

    ExpandedTimelineScreen(
        target = TimelineTarget.LFO,
        state = TimelineInstanceState(
            config = DuoTimelineConfig.LFO.copy(enabled = true, durationSeconds = 45f),
            pathA = samplePath,
            pathB = TimelinePath(),
            currentPosition = 0.4f
        ),
        selectedParam = 0,
        color = OrpheusColors.neonCyan,
        onDurationChange = {},
        onPlaybackModeChange = {},
        onEnabledChange = {},
        onParamSelect = {},
        onPathStarted = { _, _ -> },
        onPointAdded = { _, _ -> },
        onPointsRemovedAfter = { _, _ -> },
        onPathCompleted = { _, _ -> },
        onClearPath = {},
        onSave = {},
        onCancel = {},
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    )
}
