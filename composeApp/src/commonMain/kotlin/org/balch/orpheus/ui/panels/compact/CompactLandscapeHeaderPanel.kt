package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.presets.PresetPanelActions
import org.balch.orpheus.features.presets.PresetUiState
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VizPanelActions
import org.balch.orpheus.ui.viz.VizUiState
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLandscapeHeaderPanel(
    presetFeature: ViewModelStateActionMapper<PresetUiState, PresetPanelActions>,
    vizFeature: ViewModelStateActionMapper<VizUiState, VizPanelActions>,
    voiceFeature: ViewModelStateActionMapper<VoiceUiState, VoicePanelActions>,
    presetDropdownExpanded: Boolean,
    onPresetDropdownExpandedChange: (Boolean) -> Unit,
    vizDropdownExpanded: Boolean,
    onVizDropdownExpandedChange: (Boolean) -> Unit,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    modifier: Modifier = Modifier
) {
    val presetState = presetFeature.state
    val presetActions = presetFeature.actions
    val vizState = vizFeature.state
    val vizActions = vizFeature.actions
    val voiceState = voiceFeature.state
    val voiceActions = voiceFeature.actions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Title
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (liquidState != null) {
                        Modifier.liquidVizEffects(
                            liquidState = liquidState,
                            scope = effects.top,
                            frostAmount = 4.dp,
                            color = Color(0xFF2A2A3A),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else Modifier.background(Color(0xFF2A2A3A))
                )
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ORPHEUS-8",
                style = MaterialTheme.typography.titleMedium,
                color = OrpheusColors.neonCyan,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Center: Dropdowns
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Patch dropdown
            ExposedDropdownMenuBox(
                expanded = presetDropdownExpanded,
                onExpandedChange = onPresetDropdownExpandedChange
            ) {
                Box(
                    modifier = Modifier
                        .menuAnchor()
                        .width(140.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (liquidState != null) {
                                Modifier.liquidVizEffects(
                                    liquidState = liquidState,
                                    scope = effects.top,
                                    frostAmount = 4.dp,
                                    color = Color(0xFF2A2A3A),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else Modifier.background(Color(0xFF2A2A3A))
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .clickable { onPresetDropdownExpandedChange(!presetDropdownExpanded) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = presetState.selectedPreset?.name ?: "Init Patch",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetDropdownExpanded)
                    }
                }
                ExposedDropdownMenu(
                    expanded = presetDropdownExpanded,
                    onDismissRequest = { onPresetDropdownExpandedChange(false) },
                    modifier = if (liquidState != null) {
                        Modifier.liquidVizEffects(
                            liquidState = liquidState,
                            scope = effects.top,
                            frostAmount = 8.dp,
                            color = Color(0xFF2A2A3A),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else Modifier.background(Color(0xFF2A2A3A))
                ) {
                    presetState.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                presetActions.onPresetSelect(preset)
                                onPresetDropdownExpandedChange(false)
                            }
                        )
                    }
                }
            }

            // Viz dropdown
            ExposedDropdownMenuBox(
                expanded = vizDropdownExpanded,
                onExpandedChange = onVizDropdownExpandedChange
            ) {
                Box(
                    modifier = Modifier
                        .menuAnchor()
                        .width(140.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (liquidState != null) {
                                Modifier.liquidVizEffects(
                                    liquidState = liquidState,
                                    scope = effects.top,
                                    frostAmount = 4.dp,
                                    color = Color(0xFF2A2A3A),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else Modifier.background(Color(0xFF2A2A3A))
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .clickable { onVizDropdownExpandedChange(!vizDropdownExpanded) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = vizState.selectedViz.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vizDropdownExpanded)
                    }
                }
                ExposedDropdownMenu(
                    expanded = vizDropdownExpanded,
                    onDismissRequest = { onVizDropdownExpandedChange(false) },
                    modifier = if (liquidState != null) {
                        Modifier.liquidVizEffects(
                            liquidState = liquidState,
                            scope = effects.top,
                            frostAmount = 8.dp,
                            color = Color(0xFF2A2A3A),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else Modifier.background(Color(0xFF2A2A3A))
                ) {
                    vizState.visualizations.forEach { viz ->
                        DropdownMenuItem(
                            text = { Text(viz.name, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                vizActions.onSelectViz(viz)
                                onVizDropdownExpandedChange(false)
                            }
                        )
                    }
                }
            }

            PeakLed(level = voiceState.peakLevel)
            
            Box(modifier = Modifier.size(36.dp)) {
                org.balch.orpheus.ui.widgets.RotaryKnob(
                    value = voiceState.masterVolume,
                    onValueChange = voiceActions.onMasterVolumeChange,
                    range = 0f..1f,
                    size = 36.dp,
                    progressColor = OrpheusColors.neonCyan
                )
            }
        }
    }
}

@Composable
private fun PeakLed(level: Float) {
    val size = 8.dp
    val active = level > 0.01f
    val clipping = level > 0.95f
    
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                when {
                    clipping -> Color.Red
                    active -> OrpheusColors.neonCyan.copy(alpha = 0.5f + (level * 0.5f))
                    else -> Color(0xFF1A1A2A)
                }
            )
            .border(
                1.dp, 
                if (clipping) Color.Red else OrpheusColors.neonCyan.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.CircleShape
            )
    )
}

@Preview(widthDp = 600)
@Composable
private fun CompactLandscapeHeaderPanelPreview() {
    CompactLandscapeHeaderPanel(
        presetFeature = PresetsViewModel.PREVIEW,
        vizFeature = VizViewModel.PREVIEW,
        voiceFeature = VoiceViewModel.PREVIEW,
        presetDropdownExpanded = false,
        onPresetDropdownExpandedChange = {},
        vizDropdownExpanded = false,
        onVizDropdownExpandedChange = {},
        liquidState = null,
        effects = VisualizationLiquidEffects()
    )
}
