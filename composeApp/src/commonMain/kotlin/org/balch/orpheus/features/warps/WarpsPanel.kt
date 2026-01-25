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
import org.balch.orpheus.core.audio.dsp.synth.warps.WarpsSource
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.SegmentedAlgoKnob

@Composable
fun WarpsPanel(
    feature: WarpsFeature = WarpsViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    val panelColor = OrpheusColors.warpsGreen
    val knobTrackColor = OrpheusColors.warpsDarkGreen
    val knobProgressColor = panelColor
    val knobColor = OrpheusColors.warpsYellow
    val labelColor = panelColor

    CollapsibleColumnPanel(
        title = "WARP",
        color = panelColor,
        expandedTitle = "X-MOD",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Carrier Section (Left)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SourceDropdown(
                    selectedSource = state.carrierSource,
                    onSourceSelected = actions.setCarrierSource,
                    color = panelColor,
                    label = "CARRIER"
                )
                RotaryKnob(
                    value = state.carrierLevel,
                    onValueChange = actions.setCarrierLevel,
                    label = "DRIVE",
                    size = 48.dp,
                    trackColor = knobTrackColor,
                    progressColor = knobProgressColor,
                    knobColor = knobColor,
                    labelColor = labelColor,
                    controlId = "warps_carrier_level"
                )
            }

            RotaryKnob(
                modifier = Modifier.padding(top = 80.dp),
                value = state.timbre,
                onValueChange = actions.setTimbre,
                label = "TIMBRE",
                size = 42.dp,
                trackColor = knobTrackColor,
                progressColor = knobProgressColor,
                knobColor = knobColor,
                labelColor = labelColor,
                controlId = "warps_timbre"
            )

            // Big Algo Knob (Center)
            SegmentedAlgoKnob(
                modifier = Modifier.size(150.dp),
                value = state.algorithm,
                onValueChange = actions.setAlgorithm,
                controlId = "warps_algo"
            )

            RotaryKnob(
                modifier = Modifier.padding(top = 80.dp),
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                size = 42.dp,
                trackColor = knobTrackColor,
                progressColor = knobProgressColor,
                knobColor = knobColor,
                labelColor = labelColor,
                controlId = "warps_mix"
            )

            // Modulator Section (Right)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SourceDropdown(
                    selectedSource = state.modulatorSource,
                    onSourceSelected = actions.setModulatorSource,
                    color = panelColor,
                    label = "MODULATOR"
                )
                RotaryKnob(
                    value = state.modulatorLevel,
                    onValueChange = actions.setModulatorLevel,
                    label = "DRIVE",
                    size = 48.dp,
                    trackColor = knobTrackColor,
                    progressColor = knobProgressColor,
                    knobColor = knobColor,
                    labelColor = labelColor,
                    controlId = "warps_modulator_level"
                )
            }
        }
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
