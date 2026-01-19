package org.balch.orpheus.features.visualizations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

@Composable
fun VizPanel(
    feature: VizFeature = VizViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    var dropdownExpanded by remember { mutableStateOf(false) }

    CollapsibleColumnPanel(
        title = "VIZ",
        color = OrpheusColors.vizGreen,
        expandedTitle = "Fluff",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {

        // Visualization Dropdown
        var expanded by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = uiState.selectedViz.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (uiState.showKnobs) OrpheusColors.vizGreen else Color.Gray
                )
                Text(
                    text = "▼",
                    fontSize = 12.sp,
                    color = if (uiState.showKnobs) OrpheusColors.vizGreen else Color.Gray,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(OrpheusColors.softPurple) // Use explicit color
            ) {
                uiState.visualizations.forEach { viz ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                viz.name,
                                color = if (viz.id == uiState.selectedViz.id) OrpheusColors.vizGreen else Color.White
                            )
                        },
                        onClick = {
                            actions.onSelectViz(viz)
                            expanded = false
                        }
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotaryKnob(
                value = uiState.knob1Value,
                onValueChange = actions.onKnob1Change,
                label = if (uiState.showKnobs) uiState.selectedViz.knob1Label else "-",
                controlId = "viz_knob1",
                size = 64.dp,
                progressColor = if (uiState.showKnobs) OrpheusColors.vizGreen else Color.Gray.copy(
                    alpha = 0.3f
                ),
                enabled = uiState.showKnobs
            )
            RotaryKnob(
                value = uiState.knob2Value,
                onValueChange = actions.onKnob2Change,
                label = if (uiState.showKnobs) uiState.selectedViz.knob2Label else "-",
                controlId = "viz_knob2",
                size = 64.dp,
                progressColor = if (uiState.showKnobs) OrpheusColors.vizGreen else Color.Gray.copy(
                    alpha = 0.3f
                ),
                enabled = uiState.showKnobs
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun VizPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        VizPanel(
            feature = VizViewModel.previewFeature(),
            isExpanded = true
        )
    }
}
