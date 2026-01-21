package org.balch.orpheus.features.grains

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.dsp.synth.grains.GrainsMode
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.Learnable
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.learnable

@Composable
fun GrainsPanel(
    feature: GrainsFeature = GrainsViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    val panelColor = OrpheusColors.grainsRed

    CollapsibleColumnPanel(
        title = "GRAINS",
        color = panelColor,
        expandedTitle = "Cumulus",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.weight(0.5f))
                
                // Mode Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    val segColors = SegmentedButtonDefaults.colors(
                        activeContainerColor = panelColor,
                        activeContentColor = OrpheusColors.lakersPurple,
                        inactiveContentColor = panelColor,
                        inactiveContainerColor = OrpheusColors.lakersPurpleDark
                    )
                    Learnable(
                        controlId = "clouds_mode",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            GrainsMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = GrainsMode.entries.size
                                    ),
                                    onClick = { actions.setMode(mode) },
                                    selected = state.mode == mode,
                                    colors = segColors,
                                    icon = {}
                                ) {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Main content: Knobs on left, Buttons on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: Knobs in 2 rows
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Knobs row 1
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val knobTrackColor = OrpheusColors.grainsRed
                            val knobProgressColor = panelColor
                            val knobColor = OrpheusColors.fadedCyan
                            val labelColor = panelColor

                            RotaryKnob(state.position, actions.setPosition, label = "POS", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "clouds_position")
                            RotaryKnob(state.size, actions.setSize, label = "SIZE", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "clouds_size")
                            RotaryKnob(state.pitch, actions.setPitch, label = "PITCH", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "clouds_pitch")
                        }
                        
                        // Knobs row 2
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val knobTrackColor = OrpheusColors.grainsRed
                            val knobProgressColor = panelColor
                            val knobColor = OrpheusColors.fadedCyan
                            val labelColor = panelColor
                            
                            RotaryKnob(state.density, actions.setDensity, label = "DENS", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "clouds_density")
                            RotaryKnob(state.texture, actions.setTexture, label = "TEX", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "clouds_texture")
                            RotaryKnob(state.dryWet, actions.setDryWet, label = "MIX", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "clouds_mix")
                        }
                    }
                    
                    Spacer(Modifier.width(16.dp))
                    
                    // Right side: Buttons stacked vertically (TRIG on top)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Trigger (Momentary) - ON TOP
                        GrainsButton(
                            label = "TRIG",
                            active = false,
                            onClick = { 
                                try {
                                    actions.trigger()
                                } catch (e: Exception) {
                                    println("TRIG error: ${e.message}")
                                }
                            },
                            accentColor = OrpheusColors.fadedCyan,
                            controlId = "clouds_trigger"
                        )
                        
                        // Freeze Toggle - BELOW
                        GrainsButton(
                            label = "FREEZE",
                            active = state.freeze,
                            onClick = { actions.setFreeze(!state.freeze) },
                            accentColor = panelColor,
                            controlId = "clouds_freeze"
                        )
                    }
                }
                
                Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun GrainsButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
    controlId: String? = null
) {
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(32.dp)
                .then(
                    if (controlId != null) {
                        Modifier.learnable(controlId, learnState)
                    } else {
                        Modifier
                    }
                )
                .shadow(if (active) 1.dp else 4.dp, CircleShape, ambientColor = if (active) accentColor else Color.Black)
                .clip(CircleShape)
                .background(Brush.verticalGradient(if (active) listOf(accentColor, accentColor.copy(alpha=0.7f)) else listOf(OrpheusColors.metallicSurface, OrpheusColors.metallicShadow)))
                .border(1.5.dp, if (active) Color.White else accentColor.copy(alpha = 0.4f), CircleShape)
                .clickable(enabled = !isLearning) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (active) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = accentColor)
    }
}



@Preview
@Composable
fun GrainsPanelPreview() {
    OrpheusTheme {
        GrainsPanel(feature = GrainsViewModel.previewFeature(GrainsUiState(freeze = true)), isExpanded = true)
    }
}
