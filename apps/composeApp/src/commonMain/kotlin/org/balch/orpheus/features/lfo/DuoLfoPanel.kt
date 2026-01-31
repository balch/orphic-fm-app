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
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.Vertical3WaySwitch
import org.balch.orpheus.ui.widgets.VerticalToggle
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
                // 3-way AND/OFF/OR Switch (Left)
                Vertical3WaySwitch(
                    modifier =
                        Modifier
                            .learnable(ControlIds.HYPER_LFO_MODE, learnState),
                    topLabel = "AND",
                    bottomLabel = "OR",
                    position =
                        when (uiState.mode) {
                            HyperLfoMode.AND -> 0
                            HyperLfoMode.OFF -> 1
                            HyperLfoMode.OR -> 2
                        },
                    onPositionChange = { pos ->
                        actions.onModeChange(
                            when (pos) {
                                0 -> HyperLfoMode.AND
                                1 -> HyperLfoMode.OFF
                                else -> HyperLfoMode.OR
                            }
                        )
                    },
                    color = OrpheusColors.neonCyan,
                    enabled = !learnState.isActive
                )

                // Knobs (Medium size - 56dp)
                RotaryKnob(
                    value = uiState.lfoA,
                    onValueChange = actions.onLfoAChange,
                    label = "RATE 1",
                    controlId = ControlIds.HYPER_LFO_A,
                    size = 64.dp,
                    progressColor =
                        if (isActive) OrpheusColors.neonCyan
                        else OrpheusColors.neonCyan.copy(alpha = 0.4f)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LINK Vertical Switch (Right)
                VerticalToggle(
                    modifier = Modifier.learnable(ControlIds.HYPER_LFO_LINK, learnState),
                    topLabel = "LINK",
                    bottomLabel = "OFF",
                    isTop = uiState.linkEnabled,
                    onToggle = { actions.onLinkChange(it) },
                    color = OrpheusColors.neonCyan,
                    enabled = !learnState.isActive
                )
                RotaryKnob(
                    value = uiState.lfoB,
                    onValueChange = actions.onLfoBChange,
                    label = "RATE 2",
                    controlId = ControlIds.HYPER_LFO_B,
                    size = 64.dp,
                    progressColor =
                        if (isActive) OrpheusColors.neonCyan
                        else OrpheusColors.neonCyan.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun HyperLfoPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        DuoLfoPanel(
            feature = LfoViewModel.previewFeature()
        )
    }
}
