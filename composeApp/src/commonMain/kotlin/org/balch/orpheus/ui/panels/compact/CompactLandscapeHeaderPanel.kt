package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLandscapeHeaderPanel(
    selectedPresetName: String,
    presets: List<DronePreset>,
    presetDropdownExpanded: Boolean,
    onPresetDropdownExpandedChange: (Boolean) -> Unit,
    onPresetSelect: (DronePreset) -> Unit,
    selectedVizName: String,
    visualizations: List<Visualization>,
    vizDropdownExpanded: Boolean,
    onVizDropdownExpandedChange: (Boolean) -> Unit,
    onVizSelect: (Visualization) -> Unit,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
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
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedPresetName,
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
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onPresetSelect(preset)
                            onPresetDropdownExpandedChange(false)
                        }
                    )
                }
            }
        }

        // Spacer
        Spacer(Modifier.width(16.dp))

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
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedVizName,
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
                visualizations.forEach { viz ->
                    DropdownMenuItem(
                        text = { Text(viz.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onVizSelect(viz)
                            onVizDropdownExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun CompactLandscapeHeaderPanelPreview() {
    CompactLandscapeHeaderPanel(
        selectedPresetName = "Factory Patch 1",
        presets = emptyList(),
        presetDropdownExpanded = false,
        onPresetDropdownExpandedChange = {},
        onPresetSelect = {},
        selectedVizName = "Oscilloscope",
        visualizations = emptyList(),
        vizDropdownExpanded = false,
        onVizDropdownExpandedChange = {},
        onVizSelect = {},
        liquidState = null,
        effects = VisualizationLiquidEffects()
    )
}
