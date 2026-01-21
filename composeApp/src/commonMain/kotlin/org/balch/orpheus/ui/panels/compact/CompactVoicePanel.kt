package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HoldSwitch
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.SwitchOrientation

/**
 * A compact voice button with tune slider (left), pulse button, envelope speed slider (right), and hold toggle.
 */
@Composable
fun CompactVoicePanel(
    voiceIndex: Int,
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    color: Color,
    modifier: Modifier = Modifier
) {
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val actions = voiceFeature.actions

    val state = voiceState.voiceStates.getOrNull(voiceIndex) ?: return
    val tune = state.tune
    val envelopeSpeed = voiceState.voiceEnvelopeSpeeds.getOrElse(voiceIndex) { 0.5f }
    val isActive = state.pulse
    val isHolding = state.isHolding

    Column(
        modifier = modifier.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Voice number label
        Text(
            text = "${voiceIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = tune,
                onValueChange = { actions.onVoiceTuneChange(voiceIndex, it) },
                size = 32.dp,
                progressColor = color,
            )

            HoldSwitch(
                checked = isHolding,
                onCheckedChange = { actions.onHoldChange(voiceIndex, it) },
                activeColor = color,
                orientation = SwitchOrientation.Vertical
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        HorizontalMiniSlider(
            value = envelopeSpeed,
            onValueChange = { actions.onVoiceEnvelopeSpeedChange(voiceIndex, it) },
            leftLabel = "F",
            rightLabel = "S",
            color = color,
            trackWidth = 36
        )

        PulseButton(
            size = 36.dp,
            label = "",
            isActive = isActive,
            onPulseStart = { actions.onPulseStart(voiceIndex) },
            onPulseEnd = {
                actions.onPulseEnd(voiceIndex)
                actions.onWobblePulseEnd(voiceIndex)
            },
            activeColor = color,
            onPulseStartWithPosition = { x, y -> actions.onWobblePulseStart(voiceIndex, x, y) },
            onWobbleMove = { x, y -> actions.onWobbleMove(voiceIndex, x, y) }
        )
    }
}


@Preview
@Composable
private fun CompactVoicePanelPreview_Inactive() {
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 0,
            voiceFeature = VoiceViewModel.previewFeature(),
            color = OrpheusColors.neonMagenta
        )
    }
}

@Preview
@Composable
private fun CompactVoicePanelPreview_Active() {
    val state = VoiceUiState(
        voiceStates = List(12) { i ->
            org.balch.orpheus.core.audio.VoiceState(index = i, tune = 0.8f, pulse = i == 4)
        },
        voiceEnvelopeSpeeds = List(12) { 0.2f }
    )
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 4,
            voiceFeature = VoiceViewModel.previewFeature(state),
            color = OrpheusColors.synthGreen
        )
    }
}

@Preview
@Composable
private fun CompactVoicePanelPreview_Holding() {
    val state = VoiceUiState(
        voiceStates = List(12) { i ->
            org.balch.orpheus.core.audio.VoiceState(index = i, tune = 0.3f, isHolding = i == 2)
        },
        voiceEnvelopeSpeeds = List(12) { 0.9f }
    )
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 2,
            voiceFeature = VoiceViewModel.previewFeature(state),
            color = OrpheusColors.neonMagenta
        )
    }
}

@Preview
@Composable
private fun CompactVoicePanelPreview_ActiveAndHolding() {
    val state = VoiceUiState(
        voiceStates = List(12) { i ->
            org.balch.orpheus.core.audio.VoiceState(index = i, tune = 1.0f, pulse = i == 7, isHolding = i == 7)
        },
        voiceEnvelopeSpeeds = List(12) { 0.0f }
    )
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 7,
            voiceFeature = VoiceViewModel.previewFeature(state),
            color = OrpheusColors.synthGreen
        )
    }
}

@Preview(widthDp = 200, heightDp = 120)
@Composable
private fun CompactVoicePanelPreview_AllColors() {
    val state = VoiceUiState(
        voiceStates = List(12) { i ->
            org.balch.orpheus.core.audio.VoiceState(index = i, tune = 0.5f, pulse = true, isHolding = true)
        },
        voiceEnvelopeSpeeds = List(12) { 0.5f }
    )
    OrpheusTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactVoicePanel(
                voiceIndex = 0,
                voiceFeature = VoiceViewModel.previewFeature(state),
                color = OrpheusColors.neonMagenta
            )
            CompactVoicePanel(
                voiceIndex = 4,
                voiceFeature = VoiceViewModel.previewFeature(state),
                color = OrpheusColors.synthGreen
            )
        }
    }
}
