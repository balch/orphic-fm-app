package org.balch.orpheus.features.delay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.VerticalToggle

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
        expandedTitle = "Depth",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.mod1,
                onValueChange = actions.setMod1,
                label = "DELAY A",
                controlId = ControlIds.DELAY_MOD_1,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.mod2,
                onValueChange = actions.setMod2,
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
                onToggle = { actions.setSource(it) },
                color = OrpheusColors.warmGlow
            )
            VerticalToggle(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .width(32.dp),
                topLabel = "TRI",
                bottomLabel = "SQR",
                isTop = uiState.isTriangleWave,
                onToggle = { actions.setWaveform(it) },
                color = OrpheusColors.warmGlow
            )
        }

        // Row 2: TIME 1, TIME 2, FB, MIX
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.time1,
                onValueChange = actions.setTime1,
                label = "TIME A",
                controlId = ControlIds.DELAY_TIME_1,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.time2,
                onValueChange = actions.setTime2,
                label = "TIME B",
                controlId = ControlIds.DELAY_TIME_2,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.feedback,
                onValueChange = actions.setFeedback,
                label = "\u221E", // infinity
                controlId = ControlIds.DELAY_FEEDBACK,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
            RotaryKnob(
                value = uiState.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = ControlIds.DELAY_MIX,
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun ModDelayPanelPreview() {
    DelayFeedbackPanel(
        feature = DelayViewModel.previewFeature()
    )
}
