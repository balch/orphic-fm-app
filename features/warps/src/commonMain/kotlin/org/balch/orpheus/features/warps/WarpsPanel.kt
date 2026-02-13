package org.balch.orpheus.features.warps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.SegmentedAlgoKnob

private data class WarpsColors(
    val panelColor: Color = OrpheusColors.warpsGreen,
    val knobTrackColor: Color = OrpheusColors.warpsDarkGreen,
    val knobProgressColor: Color = panelColor,
    val knobColor: Color = OrpheusColors.warpsYellow,
    val labelColor: Color = panelColor,
)
@Composable
fun WarpsPanel(
    feature: WarpsFeature = WarpsViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val warpsColors = remember { WarpsColors() }

    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "BLEND",
        color = warpsColors.panelColor,
        expandedTitle = "X-Mod",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-20).dp)
        ) {

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ChannelSourceWidget(
                    controlId = "warps_carrier_level",
                    label = "CARRIER",
                    level = state.carrierLevel,
                    onLevelChange = actions.setCarrierLevel,
                    selectedSource = state.carrierSource,
                    onSelectSource = actions.setCarrierSource,
                    warpsColors = warpsColors,
                )

                // Big Algo Knob (Center)
                SegmentedAlgoKnob(
                    modifier = Modifier.size(150.dp),
                    value = state.algorithm,
                    onValueChange = actions.setAlgorithm,
                    controlId = "warps_algo"
                )

                ChannelSourceWidget(
                    controlId = "warps_modulator_level",
                    label = "MODULATOR",
                    level = state.modulatorLevel,
                    onLevelChange = actions.setModulatorLevel,
                    selectedSource = state.modulatorSource,
                    onSelectSource = actions.setModulatorSource,
                    warpsColors = warpsColors,
                )
            }

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RotaryKnob(
                    modifier = Modifier.padding(end = 44.dp),
                    value = state.timbre,
                    onValueChange = actions.setTimbre,
                    label = "TIMBRE",
                    size = 42.dp,
                    trackColor = warpsColors.knobTrackColor,
                    progressColor = warpsColors.knobProgressColor,
                    knobColor = warpsColors.knobColor,
                    labelColor = warpsColors.labelColor,
                    controlId = "warps_timbre"
                )

                RotaryKnob(
                    modifier = Modifier.padding(start = 44.dp),
                    value = state.mix,
                    onValueChange = actions.setMix,
                    label = "MIX",
                    size = 42.dp,
                    trackColor = warpsColors.knobTrackColor,
                    progressColor = warpsColors.knobProgressColor,
                    knobColor = warpsColors.knobColor,
                    labelColor = warpsColors.labelColor,
                    controlId = "warps_mix"
                )
            }
        }
    }
}

@Composable
private fun ChannelSourceWidget(
    controlId: String,
    label: String,
    level: Float,
    onLevelChange: (Float) -> Unit,
    selectedSource: WarpsSource,
    onSelectSource: (WarpsSource) -> Unit,
    warpsColors: WarpsColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SourceDropdown(
            selectedSource = selectedSource,
            onSourceSelected = onSelectSource,
            color = warpsColors.panelColor,
            label = label
        )
        RotaryKnob(
            value = level,
            onValueChange = onLevelChange,
            label = "DRIVE",
            size = 48.dp,
            trackColor = warpsColors.knobTrackColor,
            progressColor = warpsColors.knobProgressColor,
            knobColor = warpsColors.knobColor,
            labelColor = warpsColors.labelColor,
            controlId = controlId
        )
    }
}

/**
 * Compact dropdown for selecting audio source.
 */
@Composable
private fun SourceDropdown(
    selectedSource: WarpsSource,
    onSourceSelected: (WarpsSource) -> Unit,
    color: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Label above dropdown
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )

        Spacer(Modifier.height(2.dp))

        // Dropdown button
        Box(
            modifier = Modifier
                .clickable { expanded = true }
                .clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedSource.displayName,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select Source",
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(OrpheusColors.panelSurface)
            ) {
                WarpsSource.entries.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = source.displayName,
                                color = if (source == selectedSource) color else Color.White
                            )
                        },
                        onClick = {
                            onSourceSelected(source)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 600, heightDp = 400)
@Composable
fun WarpsPanelPreview() {
    OrpheusTheme {
        WarpsPanel(
            feature = WarpsViewModel.previewFeature(
                WarpsUiState(
                    algorithm = 0.3f,
                    timbre = 0.6f,
                    carrierLevel = 0.4f,
                    modulatorLevel = 0.7f,
                    carrierSource = WarpsSource.SYNTH,
                    modulatorSource = WarpsSource.DRUMS,
                    mix = 0.5f
                )
            ),
            isExpanded = true
        )
    }
}
