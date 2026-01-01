package org.balch.orpheus.features.delay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.VerticalToggle
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

/**
 * Smart wrapper that connects DelayViewModel to the layout. Collects state and dispatches events.
 */
@Composable
fun ModDelayPanel(
    modifier: Modifier = Modifier,
    viewModel: DelayViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val actions = viewModel.panelActions

    ModDelayPanelLayout(
        uiState = state,
        actions = actions,
        modifier = modifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange
    )
}

/**
 * Mod Delay panel with dual delay lines, modulation, and mix controls. Layout: Row 1: MOD 1, MOD 2,
 * LFO/SELF toggle, TRI/SQR toggle Row 2: TIME 1, TIME 2, FB, MIX
 */
@Composable
fun ModDelayPanelLayout(
    modifier: Modifier = Modifier,
    uiState: DelayUiState,
    actions: DelayPanelActions,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    CollapsibleColumnPanel(
        title = "DELAY",
        color = OrpheusColors.warmGlow,
        expandedTitle = "Mod Delay",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Row 1: MOD 1, MOD 2, LFO/SELF toggle, TRI/SQR toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                RotaryKnob(
                    value = uiState.mod1,
                    onValueChange = actions.onMod1Change,
                    label = "MOD 1",
                    controlId = null,
                    size = 40.dp,
                    progressColor = OrpheusColors.warmGlow
                )
                RotaryKnob(
                    value = uiState.mod2,
                    onValueChange = actions.onMod2Change,
                    label = "MOD 2",
                    controlId = null,
                    size = 40.dp,
                    progressColor = OrpheusColors.warmGlow
                )
                // Add top padding to toggles to align with knob centers
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    VerticalToggle(
                        topLabel = "LFO",
                        bottomLabel = "SELF",
                        isTop = uiState.isLfoSource,
                        onToggle = { actions.onSourceChange(it) },
                        color = OrpheusColors.warmGlow
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    VerticalToggle(
                        topLabel = "TRI",
                        bottomLabel = "SQR",
                        isTop = uiState.isTriangleWave,
                        onToggle = { actions.onWaveformChange(it) },
                        color = OrpheusColors.warmGlow
                    )
                }
            }

            // Row 2: TIME 1, TIME 2, FB, MIX
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = uiState.time1,
                    onValueChange = actions.onTime1Change,
                    label = "TIME 1",
                    controlId = null,
                    size = 40.dp,
                    progressColor = OrpheusColors.warmGlow
                )
                RotaryKnob(
                    value = uiState.time2,
                    onValueChange = actions.onTime2Change,
                    label = "TIME 2",
                    controlId = null,
                    size = 40.dp,
                    progressColor = OrpheusColors.warmGlow
                )
                RotaryKnob(
                    value = uiState.feedback,
                    onValueChange = actions.onFeedbackChange,
                    label = "FB",
                    controlId = null,
                    size = 40.dp,
                    progressColor = OrpheusColors.warmGlow
                )
                RotaryKnob(
                    value = uiState.mix,
                    onValueChange = actions.onMixChange,
                    label = "MIX",
                    controlId = null,
                    size = 40.dp,
                    progressColor = OrpheusColors.warmGlow
                )
            }
        }
    }
}

@Preview(widthDp = 300, heightDp = 240)
@Composable
fun ModDelayPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        ModDelayPanelLayout(
            uiState = DelayUiState(
                time1 = 0.5f,
                mod1 = 0.3f,
                time2 = 0.6f,
                mod2 = 0.4f,
                feedback = 0.7f,
                mix = 0.5f,
                isLfoSource = true,
                isTriangleWave = true
            ),
            actions = DelayPanelActions(
                onTime1Change = {},
                onMod1Change = {},
                onTime2Change = {},
                onMod2Change = {},
                onFeedbackChange = {},
                onMixChange = {},
                onSourceChange = {},
                onWaveformChange = {}
            )
        )
    }
}
