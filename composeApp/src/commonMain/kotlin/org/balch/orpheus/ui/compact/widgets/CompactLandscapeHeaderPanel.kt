package org.balch.orpheus.ui.compact.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.ui.viz.Visualization
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Patch dropdown
        ExposedDropdownMenuBox(
            expanded = presetDropdownExpanded,
            onExpandedChange = onPresetDropdownExpandedChange
        ) {
            TextField(
                value = selectedPresetName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetDropdownExpanded) },
                modifier = Modifier.menuAnchor().width(140.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(
                expanded = presetDropdownExpanded,
                onDismissRequest = { onPresetDropdownExpandedChange(false) }
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
            TextField(
                value = selectedVizName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vizDropdownExpanded) },
                modifier = Modifier.menuAnchor().width(140.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(
                expanded = vizDropdownExpanded,
                onDismissRequest = { onVizDropdownExpandedChange(false) }
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
        onVizSelect = {}
    )
}
