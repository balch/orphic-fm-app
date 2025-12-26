package org.balch.orpheus.features.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.timeline.TimelinePath
import org.balch.orpheus.features.timeline.TimelinePoint
import org.balch.orpheus.features.timeline.TweakPlaybackMode
import org.balch.orpheus.features.timeline.TweakTimelineConfig
import org.balch.orpheus.features.timeline.TweakTimelineParameter
import org.balch.orpheus.features.timeline.TweakTimelineState
import org.balch.orpheus.features.timeline.TweakTimelineViewModel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Full-screen expanded editor for multi-parameter timeline automation.
 *
 * Layout:
 * - Header: Enable toggle, cancel/save buttons
 * - Parameter Picker: Select up to 5 parameters from available list
 * - Controls: Duration slider, playback mode toggles
 * - Active Parameter Selector: Choose which parameter to draw
 * - Drawing Canvas: Draw/edit the selected parameter's path
 */
@Composable
fun ExpandedTweakTimelineScreen(
    onDismiss: (Boolean) -> Unit,
    viewModel: TweakTimelineViewModel = metroViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    ExpandedTweakTimelineLayout(
        state = uiState.timeline,
        activeParameter = uiState.activeParameter,
        onDurationChange = { viewModel.setDuration(it) },
        onPlaybackModeChange = { viewModel.setPlaybackMode(it) },
        onEnabledChange = { viewModel.setEnabled(it) },
        onAddParameter = { viewModel.addParameter(it) },
        onRemoveParameter = { viewModel.removeParameter(it) },
        onSelectActiveParameter = { viewModel.selectActiveParameter(it) },
        onPathStarted = { param, point -> viewModel.startPath(param, point) },
        onPointAdded = { param, point -> viewModel.addPoint(param, point) },
        onPointsRemovedAfter = { param, time -> viewModel.removePointsAfter(param, time) },
        onPathCompleted = { param, value -> viewModel.completePath(param, value) },
        onClearPath = { viewModel.clearPath(it) },
        onSave = {
            viewModel.save()
            onDismiss(true)
        },
        onCancel = {
            viewModel.cancel()
            onDismiss(false)
        },
        modifier = modifier
    )
}

@Composable
fun ExpandedTweakTimelineLayout(
    state: TweakTimelineState,
    activeParameter: TweakTimelineParameter?,
    onDurationChange: (Float) -> Unit,
    onPlaybackModeChange: (TweakPlaybackMode) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAddParameter: (TweakTimelineParameter) -> Unit,
    onRemoveParameter: (TweakTimelineParameter) -> Unit,
    onSelectActiveParameter: (TweakTimelineParameter?) -> Unit,
    onPathStarted: (TweakTimelineParameter, TimelinePoint) -> Unit,
    onPointAdded: (TweakTimelineParameter, TimelinePoint) -> Unit,
    onPointsRemovedAfter: (TweakTimelineParameter, Float) -> Unit,
    onPathCompleted: (TweakTimelineParameter, Float) -> Unit,
    onClearPath: (TweakTimelineParameter) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val accentColor = OrpheusColors.neonCyan

    Column(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF12121A))
            .border(2.dp, accentColor.copy(alpha = 0.4f), shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ═══════════════════════════════════════════════════════════
        // HEADER: Title Left | X and Check Right
        // ═══════════════════════════════════════════════════════════
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Title (Left)
            Text(
                text = "Tweak Timeline",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )

            // Buttons (Right)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel button (X)
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

                // Save button (Check)
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
        }

        // ═══════════════════════════════════════════════════════════
        // MAIN CONTENT: Controls Left | Canvas Right
        // ═══════════════════════════════════════════════════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ─── LEFT COLUMN: Controls ───
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Clear Button (Top)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A3A))
                        .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable(enabled = activeParameter != null) {
                            if (activeParameter != null) onClearPath(activeParameter)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CLEAR LINE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeParameter != null) accentColor else accentColor.copy(alpha = 0.4f)
                    )
                }

                // 2. Duration Slider (10s - 120s)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Duration",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${state.config.durationSeconds.toInt()}s",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    Slider(
                        value = state.config.durationSeconds,
                        onValueChange = onDurationChange,
                        valueRange = 10f..120f, // updated range 10-120s
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = accentColor.copy(alpha = 0.3f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 3. Parameter List
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0A12))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Parameters",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                     Column(
                         verticalArrangement = Arrangement.spacedBy(2.dp)
                     ) {
                         TweakTimelineParameter.entries.forEach { param ->
                             val isIncluded = param in state.config.selectedParameters
                             val isSelected = activeParameter == param
                             
                             Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) param.color.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        if (isIncluded) {
                                            onSelectActiveParameter(param)
                                        } else {
                                            if (state.config.selectedParameters.size < TweakTimelineParameter.MAX_SELECTED) {
                                                onAddParameter(param)
                                                onSelectActiveParameter(param)
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                             ) {
                                  // Checkbox
                                  Box(
                                      modifier = Modifier
                                          .size(16.dp)
                                          .clip(RoundedCornerShape(3.dp))
                                          .background(if (isIncluded) param.color else Color.Transparent)
                                          .border(1.dp, param.color, RoundedCornerShape(3.dp))
                                          .clickable {
                                              if (isIncluded) onRemoveParameter(param)
                                              else if (state.config.selectedParameters.size < TweakTimelineParameter.MAX_SELECTED) onAddParameter(param)
                                          },
                                      contentAlignment = Alignment.Center
                                  ) {
                                      if (isIncluded) {
                                          Text("✓", fontSize = 10.sp, color = Color.Black)
                                      }
                                  }
                                  
                                  Spacer(modifier = Modifier.width(8.dp))
                                  
                                  Text(
                                      text = param.label,
                                      fontSize = 12.sp,
                                      color = if (isIncluded) Color.White else Color.White.copy(alpha = 0.4f),
                                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                  )
                             }
                         }
                     }
                }
            }

            // ─── RIGHT COLUMN: Canvas ───
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            ) {
                if (activeParameter != null) {
                    val currentPath = state.paths[activeParameter] ?: TimelinePath()
                     TimelineDrawingCanvas(
                        path = currentPath,
                        currentPosition = state.currentPosition,
                        color = activeParameter.color,
                        onPathStarted = { onPathStarted(activeParameter, it) },
                        onPointAdded = { onPointAdded(activeParameter, it) },
                        onPointsRemovedAfter = { onPointsRemovedAfter(activeParameter, it) },
                        onPathCompleted = { onPathCompleted(activeParameter, it) },
                        enabled = true,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    Text(
                        text = "Editing: ${activeParameter.label}",
                        color = activeParameter.color.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    )
                } else {
                    Text(
                        text = "Select a parameter to draw",
                        color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParameterPicker(
    selectedParameters: List<TweakTimelineParameter>,
    onAddParameter: (TweakTimelineParameter) -> Unit,
    onRemoveParameter: (TweakTimelineParameter) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Parameters (${selectedParameters.size}/${TweakTimelineParameter.MAX_SELECTED})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.7f)
        )

        // Group by category
        TweakTimelineParameter.byCategory().forEach { (category, params) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$category:",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.width(40.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    params.forEach { param ->
                        val isSelected = param in selectedParameters
                        val canAdd = selectedParameters.size < TweakTimelineParameter.MAX_SELECTED

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) param.color.copy(alpha = 0.3f) else Color(0xFF2A2A3A))
                                .border(1.dp, param.color.copy(alpha = if (isSelected) 0.6f else 0.3f), RoundedCornerShape(4.dp))
                                .clickable(enabled = isSelected || canAdd) {
                                    if (isSelected) onRemoveParameter(param)
                                    else onAddParameter(param)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = param.label,
                                fontSize = 9.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected || canAdd) param.color else param.color.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveParameterSelector(
    selectedParameters: List<TweakTimelineParameter>,
    activeParameter: TweakTimelineParameter?,
    onSelect: (TweakTimelineParameter?) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, OrpheusColors.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        selectedParameters.forEach { param ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (activeParameter == param) param.color.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { onSelect(param) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = param.label,
                    fontSize = 11.sp,
                    fontWeight = if (activeParameter == param) FontWeight.Bold else FontWeight.Normal,
                    color = if (activeParameter == param) param.color else param.color.copy(alpha = 0.6f)
                )
            }
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
    mode: TweakPlaybackMode,
    onModeChange: (TweakPlaybackMode) -> Unit,
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
        TweakPlaybackMode.entries.forEach { m ->
            val label = when (m) {
                TweakPlaybackMode.ONCE -> "→"
                TweakPlaybackMode.LOOP -> "⟳"
                TweakPlaybackMode.PING_PONG -> "↔"
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

@Preview(widthDp = 800, heightDp = 600)
@Composable
private fun ExpandedTweakTimelineScreenPreview() {
    val samplePath = TimelinePath(
        points = listOf(
            TimelinePoint(0f, 0.5f),
            TimelinePoint(0.3f, 0.8f),
            TimelinePoint(0.6f, 0.2f),
            TimelinePoint(1f, 0.6f)
        ),
        isComplete = true
    )

    ExpandedTweakTimelineLayout(
        state = TweakTimelineState(
            config = TweakTimelineConfig(
                enabled = true,
                durationSeconds = 45f,
                selectedParameters = listOf(
                    TweakTimelineParameter.LFO_FREQ_A,
                    TweakTimelineParameter.DELAY_TIME_1,
                    TweakTimelineParameter.DIST_DRIVE
                )
            ),
            paths = mapOf(
                TweakTimelineParameter.LFO_FREQ_A to samplePath,
                TweakTimelineParameter.DELAY_TIME_1 to TimelinePath(),
                TweakTimelineParameter.DIST_DRIVE to TimelinePath()
            ),
            currentPosition = 0.4f
        ),
        activeParameter = TweakTimelineParameter.LFO_FREQ_A,
        onDurationChange = {},
        onPlaybackModeChange = {},
        onEnabledChange = {},
        onAddParameter = {},
        onRemoveParameter = {},
        onSelectActiveParameter = {},
        onPathStarted = { _, _ -> },
        onPointAdded = { _, _ -> },
        onPointsRemovedAfter = { _, _ -> },
        onPathCompleted = { _, _ -> },
        onClearPath = {},
        onSave = {},
        onCancel = {},
        modifier = Modifier.fillMaxSize().padding(16.dp)
    )
}
