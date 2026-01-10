package org.balch.orpheus.features.resonator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * LA Lakers color scheme for Rings resonator panel
 * Gold theme throughout
 */
private val RingsPanelColor = OrpheusColors.lakersGold      // Gold for panel header and accents

/**
 * Resonator panel (Rings port) with controls for:
 * - Enable toggle
 * - Mode selector (Modal / String / Sympathetic)
 * - STRUCTURE: Material/inharmonicity
 * - BRIGHTNESS: High frequency content
 * - DAMPING: Decay time
 * - POSITION: Excitation point
 * - MIX: Dry/wet blend
 */
@Composable
fun ResonatorPanel(
    feature: ResonatorFeature = ResonatorViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "REZO",
        color = RingsPanelColor,
        expandedTitle = "Resonator",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        ResonatorPanelContent(state, actions)
    }
}

@Composable
private fun ResonatorPanelContent(
    state: ResonatorUiState,
    actions: ResonatorPanelActions
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Combined Enable/Mode selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val segColors = SegmentedButtonDefaults.colors(
                    activeContainerColor = RingsPanelColor,
                    activeContentColor = OrpheusColors.lakersPurple,
                    inactiveContentColor = OrpheusColors.lakersGold,
                    inactiveContainerColor = OrpheusColors.lakersPurpleDark
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 8.dp),

                ) {
                    // "Off" option
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                        onClick = { actions.setEnabled(false) },
                        selected = !state.enabled,
                        colors = segColors,
                        icon = {}
                    ) {
                        Text(
                            text = "Off",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }

                    // Regular modes
                    ResonatorMode.entries.forEachIndexed { index, mode ->
                        val buttonIndex = index + 1
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = buttonIndex, count = 4),
                            onClick = { 
                                actions.setEnabled(true)
                                actions.setMode(mode) 
                            },
                            selected = state.enabled && state.mode == mode,
                            colors = segColors,
                            icon = {}
                        ) {
                            Text(
                                text = mode.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Knobs row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = state.structure,
                    onValueChange = actions.setStructure,
                    label = "STRUCT",
                    size = 40.dp,
                    trackColor = OrpheusColors.lakersPurpleDark,
                    progressColor = RingsPanelColor,
                    knobColor = OrpheusColors.lakersGold,
                    labelColor = RingsPanelColor
                )
                RotaryKnob(
                    value = state.brightness,
                    onValueChange = actions.setBrightness,
                    label = "BRIGHT",
                    size = 40.dp,
                    trackColor = OrpheusColors.lakersPurpleDark,
                    progressColor = RingsPanelColor,
                    knobColor = OrpheusColors.lakersGold,
                    labelColor = RingsPanelColor
                )
                RotaryKnob(
                    value = state.damping,
                    onValueChange = actions.setDamping,
                    label = "DAMP",
                    size = 40.dp,
                    trackColor = OrpheusColors.lakersPurpleDark,
                    progressColor = RingsPanelColor,
                    knobColor = OrpheusColors.lakersGold,
                    labelColor = RingsPanelColor
                )
                RotaryKnob(
                    value = state.position,
                    onValueChange = actions.setPosition,
                    label = "POSN",
                    size = 40.dp,
                    trackColor = OrpheusColors.lakersPurpleDark,
                    progressColor = RingsPanelColor,
                    knobColor = OrpheusColors.lakersGold,
                    labelColor = RingsPanelColor
                )
                RotaryKnob(
                    value = state.mix,
                    onValueChange = actions.setMix,
                    label = "MIX",
                    size = 40.dp,
                    trackColor = OrpheusColors.lakersPurpleDark,
                    progressColor = RingsPanelColor,
                    knobColor = OrpheusColors.lakersGold,
                    labelColor = RingsPanelColor
                )
            }
        }
    }
}

@Preview
@Composable
fun ResonatorPanelPreview() {
    OrpheusTheme {
        ResonatorPanel(
            feature = ResonatorViewModel.previewFeature(
                ResonatorUiState(enabled = true)
            ),
            isExpanded = true
        )
    }
}
