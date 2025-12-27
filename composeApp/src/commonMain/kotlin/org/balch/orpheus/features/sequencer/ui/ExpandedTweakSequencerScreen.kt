package org.balch.orpheus.features.sequencer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.sequencer.SequencerPath
import org.balch.orpheus.features.sequencer.SequencerPoint
import org.balch.orpheus.features.sequencer.TweakSequencerConfig
import org.balch.orpheus.features.sequencer.TweakSequencerParameter
import org.balch.orpheus.features.sequencer.TweakSequencerState
import org.balch.orpheus.features.sequencer.TweakSequencerViewModel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.CompactSecondsSlider
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Full-screen expanded editor for multi-parameter sequencer automation.
 *
 * Layout:
 * - Header: Enable toggle, cancel/save buttons
 * - Parameter Picker: Select up to 5 parameters from available list
 * - Controls: Duration slider, playback mode toggles
 * - Active Parameter Selector: Choose which parameter to draw
 * - Drawing Canvas: Draw/edit the selected parameter's path
 */
@Composable
fun ExpandedTweakSequencerScreen(
    onDismiss: (Boolean) -> Unit,
    viewModel: TweakSequencerViewModel = metroViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    ExpandedTweakSequencerLayout(
        state = uiState.sequencer,
        activeParameter = uiState.activeParameter,
        onDurationChange = { viewModel.setDuration(it) },
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
fun ExpandedTweakSequencerLayout(
    state: TweakSequencerState,
    activeParameter: TweakSequencerParameter?,
    onDurationChange: (Float) -> Unit,
    onAddParameter: (TweakSequencerParameter) -> Unit,
    onRemoveParameter: (TweakSequencerParameter) -> Unit,
    onSelectActiveParameter: (TweakSequencerParameter?) -> Unit,
    onPathStarted: (TweakSequencerParameter, SequencerPoint) -> Unit,
    onPointAdded: (TweakSequencerParameter, SequencerPoint) -> Unit,
    onPointsRemovedAfter: (TweakSequencerParameter, Float) -> Unit,
    onPathCompleted: (TweakSequencerParameter, Float) -> Unit,
    onClearPath: (TweakSequencerParameter) -> Unit,
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
                text = "Tweak Seq",
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
                // 1. Parameter List
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
                         verticalArrangement = Arrangement.spacedBy(2.dp),
                         modifier = Modifier.verticalScroll(rememberScrollState())
                     ) {
                         TweakSequencerParameter.entries.forEach { param ->
                             val isIncluded = param in state.config.selectedParameters
                             val isSelected = activeParameter == param
                             val hasPath = state.paths[param]?.points?.isNotEmpty() == true
                             
                             Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) param.color.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        if (isIncluded) {
                                            onSelectActiveParameter(param)
                                        } else {
                                            if (state.config.selectedParameters.size < TweakSequencerParameter.MAX_SELECTED) {
                                                onAddParameter(param)
                                                onSelectActiveParameter(param)
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                             ) {
                                  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                      // Checkbox
                                      Box(
                                          modifier = Modifier
                                              .size(16.dp)
                                              .clip(RoundedCornerShape(3.dp))
                                              .background(if (isIncluded) param.color else Color.Transparent)
                                              .border(1.dp, param.color, RoundedCornerShape(3.dp))
                                              .clickable {
                                                  if (isIncluded) {
                                                      onRemoveParameter(param)
                                                      onClearPath(param)
                                                  }
                                                  else if (state.config.selectedParameters.size < TweakSequencerParameter.MAX_SELECTED) {
                                                       onAddParameter(param)
                                                       onSelectActiveParameter(param)
                                                  }
                                              },
                                          contentAlignment = Alignment.Center
                                      ) {
                                          if (isIncluded) {
                                              Text("✓", fontSize = 10.sp, color = Color.Black)
                                          }
                                      }
                                      
                                      Spacer(modifier = Modifier.width(8.dp))
                                      
                                      Column {
                                          Text(
                                              text = param.label,
                                              fontSize = 12.sp,
                                              color = if (isIncluded) Color.White else Color.White.copy(alpha = 0.4f),
                                              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                              maxLines = 1
                                          )
                                          // Small Category Label
                                          Text(
                                              text = param.category,
                                              fontSize = 9.sp,
                                              color = if (isIncluded) param.color.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f),
                                              lineHeight = 10.sp
                                          )
                                      }
                                  }

                                  // Clear button inside item
                                  if (isIncluded && hasPath) {
                                      Box(
                                          modifier = Modifier
                                              .size(16.dp)
                                              .clip(CircleShape)
                                              .background(Color.White.copy(alpha = 0.1f))
                                              .clickable { onClearPath(param) },
                                          contentAlignment = Alignment.Center
                                      ) {
                                          Canvas(modifier = Modifier.size(8.dp)) {
                                              val strokeWidth = 1.dp.toPx()
                                              val color = Color.White.copy(alpha = 0.7f)
                                              drawLine(
                                                  color = color,
                                                  start = Offset(0f, 0f),
                                                  end = Offset(size.width, size.height),
                                                  strokeWidth = strokeWidth
                                              )
                                              drawLine(
                                                  color = color,
                                                  start = Offset(size.width, 0f),
                                                  end = Offset(0f, size.height),
                                                  strokeWidth = strokeWidth
                                              )
                                          }
                                      }
                                  }
                             }
                         }
                     }
                }

                // 2. Compact Duration Slider (Bottom)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Duration",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "${state.config.durationSeconds.toInt()}s",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    CompactSecondsSlider(
                        valueSeconds = state.config.durationSeconds,
                        onValueChange = onDurationChange,
                        color = accentColor,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                     SequencerDrawingCanvas(
                        paths = state.paths,
                        activeParameter = activeParameter,
                        currentPosition = state.currentPosition,
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

@Preview(widthDp = 800, heightDp = 600)
@Composable
private fun ExpandedTweakSequencerScreenPreview() {
    val samplePath = SequencerPath(
        points = listOf(
            SequencerPoint(0f, 0.5f),
            SequencerPoint(0.3f, 0.8f),
            SequencerPoint(0.6f, 0.2f),
            SequencerPoint(1f, 0.6f)
        ),
        isComplete = true
    )

    ExpandedTweakSequencerLayout(
        state = TweakSequencerState(
            config = TweakSequencerConfig(
                enabled = true,
                durationSeconds = 45f,
                selectedParameters = listOf(
                    TweakSequencerParameter.LFO_FREQ_A,
                    TweakSequencerParameter.DELAY_TIME_1,
                    TweakSequencerParameter.DIST_DRIVE
                )
            ),
            paths = mapOf(
                TweakSequencerParameter.LFO_FREQ_A to samplePath,
                TweakSequencerParameter.DELAY_TIME_1 to SequencerPath(),
                TweakSequencerParameter.DIST_DRIVE to SequencerPath()
            ),
            currentPosition = 0.4f
        ),
        activeParameter = TweakSequencerParameter.LFO_FREQ_A,
        onDurationChange = {},
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
