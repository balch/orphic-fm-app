package org.balch.orpheus.features.tweaker.ui

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
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.features.tweaker.SequencerPath
import org.balch.orpheus.features.tweaker.SequencerPoint
import org.balch.orpheus.features.tweaker.TweakSequencerConfig
import org.balch.orpheus.features.tweaker.TweakSequencerPanelActions
import org.balch.orpheus.features.tweaker.TweakSequencerParameter
import org.balch.orpheus.features.tweaker.TweakSequencerState
import org.balch.orpheus.features.tweaker.TweakSequencerUiState
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
    sequencerFeature: SynthFeature<TweakSequencerUiState, TweakSequencerPanelActions>,
    onDismiss: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by sequencerFeature.stateFlow.collectAsState()
    val actions = sequencerFeature.actions
    val state = uiState.sequencer
    val activeParameter = uiState.activeParameter

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
                        .clickable {
                            actions.onCancel()
                            onDismiss(false)
                        },
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
                        .clickable {
                            actions.onSave()
                            onDismiss(true)
                        },
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
                                            actions.onSelectActiveParameter(param)
                                        } else {
                                            if (state.config.selectedParameters.size < TweakSequencerParameter.MAX_SELECTED) {
                                                actions.onAddParameter(param)
                                                actions.onSelectActiveParameter(param)
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
                                                      actions.onRemoveParameter(param)
                                                      actions.onClearPath(param)
                                                  }
                                                  else if (state.config.selectedParameters.size < TweakSequencerParameter.MAX_SELECTED) {
                                                       actions.onAddParameter(param)
                                                       actions.onSelectActiveParameter(param)
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
                                              .clickable { actions.onClearPath(param) },
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
                        onValueChange = { actions.onSetDuration(it) },
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
                        onPathStarted = { actions.onStartPath(activeParameter, it) },
                        onPointAdded = { actions.onAddPoint(activeParameter, it) },
                        onPointsRemovedAfter = { actions.onRemovePointsAfter(activeParameter, it) },
                        onPathCompleted = { actions.onCompletePath(activeParameter, it) },
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
    
    // We can use the PREVIEW mapper from the ViewModel
    // but here we manually construct one to show the path data which is specific to this preview
    val previewState = TweakSequencerUiState(
        sequencer = TweakSequencerState(
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
        activeParameter = TweakSequencerParameter.LFO_FREQ_A
    )
    
    val previewActions = TweakSequencerPanelActions(
        onPlay = {}, onPause = {}, onStop = {}, onTogglePlayPause = {},
        onStartPath = { _, _ -> }, onAddPoint = { _, _ -> }, onRemovePointsAfter = { _, _ -> },
        onClearPath = {}, onCompletePath = { _, _ -> },
        onAddParameter = {}, onRemoveParameter = {}, onSelectActiveParameter = {},
        onSetDuration = {}, onSetPlaybackMode = {}, onSetEnabled = {},
        onExpand = {}, onCollapse = {}, onSave = {}, onCancel = {}
    )

    val previewFeature = object : SynthFeature<TweakSequencerUiState, TweakSequencerPanelActions> {
        override val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(previewState)
        override val actions = previewActions
    }

    ExpandedTweakSequencerScreen(
        sequencerFeature = previewFeature,
        onDismiss = {},
        modifier = Modifier.fillMaxSize().padding(16.dp)
    )
}
