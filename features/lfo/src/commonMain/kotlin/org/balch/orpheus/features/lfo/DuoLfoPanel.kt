package org.balch.orpheus.features.lfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider
import org.balch.orpheus.ui.widgets.HorizontalSwitch3Way
import org.balch.orpheus.ui.widgets.HorizontalToggle
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.Switch3WayState
import org.balch.orpheus.ui.widgets.learnable

/**
 * HyperLfoPanel consuming feature() interface.
 */
@Composable
fun DuoLfoPanel(
    feature: LfoFeature = LfoViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "LFO",
        color = OrpheusColors.neonCyan,
        expandedTitle = "Pong",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {

        val learnState = LocalLearnModeState.current
        val isActive = uiState.mode != HyperLfoMode.OFF

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 3-way AND/OFF/OR Switch (Left) - Horizontal
                HorizontalSwitch3Way(
                    modifier =
                        Modifier
                            .learnable(ControlIds.HYPER_LFO_MODE, learnState),
                    startText = "&&",
                    endText = "||",
                    state = when (uiState.mode) {
                        HyperLfoMode.AND -> Switch3WayState.START
                        HyperLfoMode.OFF -> Switch3WayState.MIDDLE
                        HyperLfoMode.OR -> Switch3WayState.END
                    },
                    onStateChange = { newState ->
                        val newMode = when (newState) {
                            Switch3WayState.START -> HyperLfoMode.AND
                            Switch3WayState.MIDDLE -> HyperLfoMode.OFF
                            Switch3WayState.END -> HyperLfoMode.OR
                        }
                        actions.setMode(newMode)
                    },
                    color = OrpheusColors.neonCyan,
                )

                // Knobs (Medium size - 56dp)
                RotaryKnob(
                    value = uiState.lfoA,
                    onValueChange = actions.setLfoA,
                    label = "RATE 1",
                    controlId = ControlIds.HYPER_LFO_A,
                    size = 64.dp,
                    progressColor =
                        if (isActive) OrpheusColors.neonCyan
                        else OrpheusColors.neonCyan.copy(alpha = 0.4f)
                )

                HorizontalMiniSlider(
                    value = uiState.lfoAMultiplier,
                    onValueChange = actions.setLfoAMultiplier,
                    leftLabel = "🐇", // Fast
                    rightLabel = "🐢", // Slow
                    color = OrpheusColors.neonCyan,
                    trackWidth = 48
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LINK Toggle (Right) - Horizontal
                HorizontalToggle(
                    modifier = Modifier.learnable(ControlIds.HYPER_LFO_LINK, learnState),
                    startLabel = "🔗", // Link
                    endLabel = "○", // Off (or ∅)
                    isStart = uiState.linkEnabled,
                    onToggle = { actions.setLink(it) },
                    color = OrpheusColors.neonCyan
                )
                RotaryKnob(
                    value = uiState.lfoB,
                    onValueChange = actions.setLfoB,
                    label = "RATE 2",
                    controlId = ControlIds.HYPER_LFO_B,
                    size = 64.dp,
                    progressColor =
                        if (isActive) OrpheusColors.neonCyan
                        else OrpheusColors.neonCyan.copy(alpha = 0.4f)
                )

                HorizontalMiniSlider(
                    value = uiState.lfoBMultiplier,
                    onValueChange = actions.setLfoBMultiplier,
                    leftLabel = "🐇", // Fast
                    rightLabel = "🐢", // Slow
                    color = OrpheusColors.neonCyan,
                    trackWidth = 48
                )
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun HyperLfoPanelPreview() {
    DuoLfoPanel(
        feature = LfoViewModel.previewFeature()
    )
}
