package org.balch.orpheus.features.viz

import androidx.compose.foundation.background
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
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.widgets.RotaryKnob

// VIZ panel color - lighter lime green for visibility
private val VizColor = Color(0xFF90EE90)  // Light green

/**
 * VIZ Panel for controlling background visualizations.
 * Shows "VIZ" when collapsed, "Background" as expanded header.
 * Uses a dropdown to select active visualization.
 */
@Composable
fun VizPanel(
    modifier: Modifier = Modifier,
    viewModel: VizViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()

    VizPanelLayout(
        currentViz = state.selectedViz,
        visualizations = state.visualizations,
        showKnobs = state.showKnobs,
        onSelectViz = viewModel::selectVisualization,
        onKnob1Change = viewModel::onKnob1Change,
        onKnob2Change = viewModel::onKnob2Change,
        modifier = modifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange
    )
}

@Composable
fun VizPanelLayout(
    currentViz: Visualization,
    visualizations: List<Visualization>,
    showKnobs: Boolean,
    onSelectViz: (Visualization) -> Unit,
    onKnob1Change: (Float) -> Unit,
    onKnob2Change: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    CollapsibleColumnPanel(
        title = "VIZ",
        color = VizColor,
        expandedTitle = "Background",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        expandedWidth = 140.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Visualization Dropdown
            var expanded by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = currentViz.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (showKnobs) VizColor else Color.Gray
                    )
                    Text(
                        text = "▼",
                        fontSize = 12.sp,
                        color = if (showKnobs) VizColor else Color.Gray,
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(OrpheusColors.softPurple) // Use explicit color
                ) {
                    visualizations.forEach { viz ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    viz.name,
                                    color = if (viz.id == currentViz.id) VizColor else Color.White
                                )
                            },
                            onClick = {
                                onSelectViz(viz)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Knobs - Visible but disabled if Off
            // Note: Since we don't pull values from interface yet, using 0.5f placeholders visually 
            // but functional updates work.
            // Ideally we'd cast or expose values.
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RotaryKnob(
                    value = 0.5f, // Placeholder visual value
                    onValueChange = onKnob1Change,
                    label = if (showKnobs) currentViz.knob1Label else "-",
                    controlId = "viz_knob1", // Not persisted for now
                    size = 48.dp,
                    progressColor = if (showKnobs) VizColor else Color.Gray.copy(alpha = 0.3f),
                    enabled = showKnobs
                )
                RotaryKnob(
                    value = 0.5f, // Placeholder visual value
                    onValueChange = onKnob2Change,
                    label = if (showKnobs) currentViz.knob2Label else "-",
                    controlId = "viz_knob2",
                    size = 48.dp,
                    progressColor = if (showKnobs) VizColor else Color.Gray.copy(alpha = 0.3f),
                    enabled = showKnobs
                )
            }
        }
    }
}


