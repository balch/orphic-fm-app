package org.balch.orpheus.features.delay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
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
 * DelayFeedbackPanel consuming feature() interface.
 */
@Composable
fun DelayFeedbackPanel(
    feature: DelayFeature = DelayViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "DELAY",
        color = OrpheusColors.warmGlow,
        expandedTitle = "Delayed Feedback",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        ModDelayPanelContent(uiState, actions)
    }
}

@Composable
private fun ModDelayPanelContent(
    uiState: DelayUiState,
    actions: DelayPanelActions
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Row 1: MOD 1, MOD 2, LFO/SELF toggle, TRI/SQR toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.mod1,
                onValueChange = actions.onMod1Change,
                label = "DELAY A",
                controlId = ControlIds.DELAY_MOD_1,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.mod2,
                onValueChange = actions.onMod2Change,
                label = "DELAY B",
                controlId = ControlIds.DELAY_MOD_2,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            VerticalToggle(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .width(32.dp),
                topLabel = "LFO",
                bottomLabel = "SELF",
                isTop = uiState.isLfoSource,
                onToggle = { actions.onSourceChange(it) },
                color = OrpheusColors.warmGlow
            )
            VerticalToggle(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .width(32.dp),
                topLabel = "TRI",
                bottomLabel = "SQR",
                isTop = uiState.isTriangleWave,
                onToggle = { actions.onWaveformChange(it) },
                color = OrpheusColors.warmGlow
            )
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
                label = "TIME A",
                controlId = ControlIds.DELAY_TIME_1,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.time2,
                onValueChange = actions.onTime2Change,
                label = "TIME B",
                controlId = ControlIds.DELAY_TIME_2,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.feedback,
                onValueChange = actions.onFeedbackChange,
                label = "\u221E", // infinity
                controlId = ControlIds.DELAY_FEEDBACK,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.mix,
                onValueChange = actions.onMixChange,
                label = "MIX",
                controlId = ControlIds.DELAY_MIX,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }
    }
}

@Preview(widthDp = 300, heightDp = 240)
@Composable
fun ModDelayPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        DelayFeedbackPanel(
            feature = DelayViewModel.previewFeature()
        )
    }
}
