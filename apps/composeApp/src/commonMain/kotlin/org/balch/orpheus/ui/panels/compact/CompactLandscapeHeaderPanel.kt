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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.presets.PresetUiState
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.AppTitleTreatment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLandscapeHeaderPanel(
    presetFeature: PresetsFeature = PresetsViewModel.feature(),
    vizFeature: VizFeature = VizViewModel.feature(),
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    presetDropdownExpanded: Boolean = false,
    onPresetDropdownExpandedChange: (Boolean) -> Unit = {},
    vizDropdownExpanded: Boolean = false,
    onVizDropdownExpandedChange: (Boolean) -> Unit = {},
    liquidState: LiquidState? = null,
    effects: VisualizationLiquidEffects = VisualizationLiquidEffects(),
    modifier: Modifier = Modifier
) {
    val presetState by presetFeature.stateFlow.collectAsState()
    val loadedPresetState = presetState as? PresetUiState.Loaded
    val presetActions = presetFeature.actions

    val vizState by vizFeature.stateFlow.collectAsState()
    val vizActions = vizFeature.actions

    val voiceState by voiceFeature.stateFlow.collectAsState()
    val voiceActions = voiceFeature.actions

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Title
        AppTitleTreatment(
            modifier = Modifier.height(36.dp),
            effects = effects,
            showSizeEffects = false,
        )

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
                                    color = OrpheusColors.panelSurface,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else Modifier.background(OrpheusColors.panelSurface)
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
                            text = loadedPresetState?.selectedPreset?.name ?: "Init Patch",
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
                    modifier = (if (liquidState != null) {
                        Modifier.liquidVizEffects(
                            liquidState = liquidState,
                            scope = effects.top,
                            frostAmount = 8.dp,
                            color = OrpheusColors.panelSurface,
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else Modifier.background(OrpheusColors.panelSurface)).background(OrpheusColors.panelSurface)
                ) {
                    loadedPresetState?.presets?.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name, style = MaterialTheme.typography.bodySmall, color = Color.White) },
                            onClick = {
                                presetActions.onApply(preset)
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
                                    color = OrpheusColors.panelSurface,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else Modifier.background(OrpheusColors.panelSurface)
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
                    modifier = (if (liquidState != null) {
                        Modifier.liquidVizEffects(
                            liquidState = liquidState,
                            scope = effects.top,
                            frostAmount = 8.dp,
                            color = OrpheusColors.panelSurface,
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else Modifier.background(OrpheusColors.panelSurface)).background(OrpheusColors.panelSurface)
                ) {
                    vizState.visualizations.forEach { viz ->
                        DropdownMenuItem(
                            text = { Text(viz.name, style = MaterialTheme.typography.bodySmall, color = Color.White) },
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
                    else -> OrpheusColors.panelBackground
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
        presetFeature = PresetsViewModel.previewFeature(),
        vizFeature = VizViewModel.previewFeature(),
        voiceFeature = VoiceViewModel.previewFeature(),
        presetDropdownExpanded = false,
        onPresetDropdownExpandedChange = {},
        vizDropdownExpanded = false,
        onVizDropdownExpandedChange = {},
        liquidState = null,
        effects = VisualizationLiquidEffects()
    )
}
