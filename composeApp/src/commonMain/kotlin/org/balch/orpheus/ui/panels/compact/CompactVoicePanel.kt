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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HoldSwitch
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.SwitchOrientation
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
    modifier: Modifier = Modifier,
    // Wobble callbacks (optional for backwards compatibility)
    onWobblePulseStart: ((Float, Float) -> Unit)? = null,
    onWobbleMove: ((Float, Float) -> Unit)? = null,
    onWobblePulseEnd: (() -> Unit)? = null
) {

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
                onValueChange = onTuneChange,
                size = 32.dp,
                progressColor = color,
            )

            HoldSwitch(
                checked = isHolding,
                onCheckedChange = { onHoldChange(it) },
                activeColor = color,
                orientation = SwitchOrientation.Vertical
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        HorizontalMiniSlider(
            value = envelopeSpeed,
            onValueChange = onEnvelopeSpeedChange,
            leftLabel = "S",
            rightLabel = "F",
            color = color,
            trackWidth = 36
        )

        PulseButton(
            size = 36.dp,
            label = "",
            isActive = isActive,
            onPulseStart = onPulseStart,
            onPulseEnd = {
                onPulseEnd()
                onWobblePulseEnd?.invoke()
            },
            activeColor = color,
            onPulseStartWithPosition = onWobblePulseStart,
            onWobbleMove = onWobbleMove
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

@Preview(widthDp = 200, heightDp = 120)
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

