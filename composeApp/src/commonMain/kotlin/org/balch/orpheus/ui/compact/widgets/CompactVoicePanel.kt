package org.balch.orpheus.ui.compact.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HoldSwitch
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A compact voice button with tune slider (left), pulse button, envelope speed slider (right), and hold toggle.
 */
@Composable
fun CompactVoicePanel(
    voiceIndex: Int,
    tune: Float,
    envelopeSpeed: Float,
    isActive: Boolean,
    isHolding: Boolean,
    onTuneChange: (Float) -> Unit,
    onEnvelopeSpeedChange: (Float) -> Unit,
    onPulseStart: () -> Unit,
    onPulseEnd: () -> Unit,
    onHoldChange: (Boolean) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tune slider (left)
        RotaryKnob(
            value = tune,
            onValueChange = onTuneChange,
            size = 28.dp,
            progressColor = color,
        )


        // Center: Voice number, pulse button, hold toggle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Voice number label
            Text(
                text = "${voiceIndex + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
            // Hold toggle button
            HoldSwitch(
                checked = isHolding,
                onCheckedChange = { onHoldChange(it) },
                activeColor = color
            )
            // Pulse button (smaller)
            PulseButton(
                size = 42.dp,
                label = "",
                isActive = isActive,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                isLearnMode = false,
                isLearning = false,
                onLearnSelect = {}
            )
        }

        // Envelope speed slider (right) with S on top, F on bottom
        VerticalMiniSlider(
            value = envelopeSpeed,
            onValueChange = onEnvelopeSpeedChange,
            topLabel = "S",
            bottomLabel = "F",
            color = color,
            trackHeight = 36
        )
    }
}

@Preview
@Composable
private fun CCompactVoicePanelPreview_Inactive() {
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 0,
            tune = 0.5f,
            envelopeSpeed = 0.5f,
            isActive = false,
            isHolding = false,
            onTuneChange = {},
            onEnvelopeSpeedChange = {},
            onPulseStart = {},
            onPulseEnd = {},
            onHoldChange = {},
            color = OrpheusColors.neonMagenta
        )
    }
}

@Preview
@Composable
private fun CompactVoicePanelPreview_Active() {
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 4,
            tune = 0.8f,
            envelopeSpeed = 0.2f,
            isActive = true,
            isHolding = false,
            onTuneChange = {},
            onEnvelopeSpeedChange = {},
            onPulseStart = {},
            onPulseEnd = {},
            onHoldChange = {},
            color = OrpheusColors.synthGreen
        )
    }
}

@Preview
@Composable
private fun CCompactVoicePanelPreview_Holding() {
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 2,
            tune = 0.3f,
            envelopeSpeed = 0.9f,
            isActive = false,
            isHolding = true,
            onTuneChange = {},
            onEnvelopeSpeedChange = {},
            onPulseStart = {},
            onPulseEnd = {},
            onHoldChange = {},
            color = OrpheusColors.neonMagenta
        )
    }
}

@Preview
@Composable
private fun CompactVoicePanelPreview_ActiveAndHolding() {
    OrpheusTheme {
        CompactVoicePanel(
            voiceIndex = 7,
            tune = 1.0f,
            envelopeSpeed = 0.0f,
            isActive = true,
            isHolding = true,
            onTuneChange = {},
            onEnvelopeSpeedChange = {},
            onPulseStart = {},
            onPulseEnd = {},
            onHoldChange = {},
            color = OrpheusColors.synthGreen
        )
    }
}

@Preview
@Composable
private fun CompactVoicePanelPreview_AllColors() {
    OrpheusTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactVoicePanel(
                voiceIndex = 0,
                tune = 0.5f,
                envelopeSpeed = 0.5f,
                isActive = true,
                isHolding = true,
                onTuneChange = {},
                onEnvelopeSpeedChange = {},
                onPulseStart = {},
                onPulseEnd = {},
                onHoldChange = {},
                color = OrpheusColors.neonMagenta
            )
            CompactVoicePanel(
                voiceIndex = 4,
                tune = 0.5f,
                envelopeSpeed = 0.5f,
                isActive = true,
                isHolding = true,
                onTuneChange = {},
                onEnvelopeSpeedChange = {},
                onPulseStart = {},
                onPulseEnd = {},
                onHoldChange = {},
                color = OrpheusColors.synthGreen
            )
        }
    }
}

