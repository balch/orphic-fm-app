package org.balch.orpheus.ui.compact.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A panel representing a Quad group (4 voices + 2 control knobs).
 *
 * @param quadIndex 0 for the first quad (voices 1-4), 1 for the second quad (voices 5-8).
 */
@Composable
fun CompactQuadPanel(
    quadIndex: Int,
    voiceStates: List<VoiceState>,
    voiceEnvelopeSpeeds: List<Float>,
    quadPitch: Float,
    quadHold: Float,
    onVoiceTuneChange: (Int, Float) -> Unit,
    onEnvelopeSpeedChange: (Int, Float) -> Unit,
    onPulseStart: (Int) -> Unit,
    onPulseEnd: (Int) -> Unit,
    onVoiceHoldChange: (Int, Boolean) -> Unit,
    onQuadPitchChange: (Float) -> Unit,
    onQuadHoldChange: (Float) -> Unit,
    color: Color,
    knobsOnLeft: Boolean = false,
    modifier: Modifier = Modifier
) {
    val startIndex = quadIndex * 4

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (knobsOnLeft) {
            QuadKnobs(
                quadPitch = quadPitch,
                quadHold = quadHold,
                onQuadPitchChange = onQuadPitchChange,
                onQuadHoldChange = onQuadHoldChange,
                controlIdPitch = ControlIds.quadPitch(quadIndex),
                controlIdHold = ControlIds.quadHold(quadIndex),
                color = color
            )
            VoiceGrid(
                startIndex = startIndex,
                voiceStates = voiceStates,
                voiceEnvelopeSpeeds = voiceEnvelopeSpeeds,
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                color = color
            )
        } else {
            VoiceGrid(
                startIndex = startIndex,
                voiceStates = voiceStates,
                voiceEnvelopeSpeeds = voiceEnvelopeSpeeds,
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                color = color
            )
            QuadKnobs(
                quadPitch = quadPitch,
                quadHold = quadHold,
                onQuadPitchChange = onQuadPitchChange,
                onQuadHoldChange = onQuadHoldChange,
                controlIdPitch = ControlIds.quadPitch(quadIndex),
                controlIdHold = ControlIds.quadHold(quadIndex),
                color = color
            )
        }
    }
}

@Composable
private fun VoiceGrid(
    startIndex: Int,
    voiceStates: List<VoiceState>,
    voiceEnvelopeSpeeds: List<Float>,
    onVoiceTuneChange: (Int, Float) -> Unit,
    onEnvelopeSpeedChange: (Int, Float) -> Unit,
    onPulseStart: (Int) -> Unit,
    onPulseEnd: (Int) -> Unit,
    onVoiceHoldChange: (Int, Boolean) -> Unit,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // First pair (Top)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0..1) {
                val voiceIndex = startIndex + i
                CompactVoicePanel(
                    voiceIndex = voiceIndex,
                    tune = voiceStates[voiceIndex].tune,
                    envelopeSpeed = voiceEnvelopeSpeeds[voiceIndex],
                    isActive = voiceStates[voiceIndex].pulse,
                    isHolding = voiceStates[voiceIndex].isHolding,
                    onTuneChange = { onVoiceTuneChange(voiceIndex, it) },
                    onEnvelopeSpeedChange = { onEnvelopeSpeedChange(voiceIndex, it) },
                    onPulseStart = { onPulseStart(voiceIndex) },
                    onPulseEnd = { onPulseEnd(voiceIndex) },
                    onHoldChange = { onVoiceHoldChange(voiceIndex, it) },
                    color = color
                )
            }
        }
        // Second pair (Bottom)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 2..3) {
                val voiceIndex = startIndex + i
                CompactVoicePanel(
                    voiceIndex = voiceIndex,
                    tune = voiceStates[voiceIndex].tune,
                    envelopeSpeed = voiceEnvelopeSpeeds[voiceIndex],
                    isActive = voiceStates[voiceIndex].pulse,
                    isHolding = voiceStates[voiceIndex].isHolding,
                    onTuneChange = { onVoiceTuneChange(voiceIndex, it) },
                    onEnvelopeSpeedChange = { onEnvelopeSpeedChange(voiceIndex, it) },
                    onPulseStart = { onPulseStart(voiceIndex) },
                    onPulseEnd = { onPulseEnd(voiceIndex) },
                    onHoldChange = { onVoiceHoldChange(voiceIndex, it) },
                    color = color
                )
            }
        }
    }
}

@Composable
private fun QuadKnobs(
    quadPitch: Float,
    quadHold: Float,
    onQuadPitchChange: (Float) -> Unit,
    onQuadHoldChange: (Float) -> Unit,
    controlIdPitch: String,
    controlIdHold: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RotaryKnob(
            value = quadPitch,
            onValueChange = onQuadPitchChange,
            controlId = controlIdPitch,
            size = 38.dp,
            progressColor = color
        )
        RotaryKnob(
            value = quadHold,
            onValueChange = onQuadHoldChange,
            controlId = controlIdHold,
            size = 38.dp,
            progressColor = color
        )
    }
}

@Preview
@Composable
private fun CompactQuadPanelPreview() {
    val mockVoiceState = VoiceState(index = 0, tune = 0.5f)
    val mockVoiceStates = List(8) { mockVoiceState }
    val mockEnvelopeSpeeds = List(8) { 0.5f }

    CompactQuadPanel(
        quadIndex = 0,
        voiceStates = mockVoiceStates,
        voiceEnvelopeSpeeds = mockEnvelopeSpeeds,
        quadPitch = 0.5f,
        quadHold = 0.2f,
        onVoiceTuneChange = { _, _ -> },
        onEnvelopeSpeedChange = { _, _ -> },
        onPulseStart = {},
        onPulseEnd = {},
        onVoiceHoldChange = { _, _ -> },
        onQuadPitchChange = {},
        onQuadHoldChange = {},
        color = OrpheusColors.neonMagenta
    )
}
