package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.HorizontalEnvelopeSlider
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun VoiceColumnMod(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    pairIndex: Int,
    tune: Float,
    modDepth: Float,
    envSpeed: Float,
    voiceActions: VoiceActions,
) {
    Column(
        modifier =
            modifier.clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        RotaryKnob(
            value = modDepth,
            onValueChange = { voiceActions.onDuoModDepthChange(pairIndex, it) },
            label = "\u0394",  // delta
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.voiceFmDepth(voiceIndex),
            size = 28.dp,
            progressColor = OrpheusColors.neonMagenta
        )
        RotaryKnob(
            value = tune,
            onValueChange = { voiceActions.onVoiceTuneChange(voiceIndex, it) },
            label = "\u266B", // eighth notes
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.voiceTune(voiceIndex),
            size = 28.dp,
            progressColor = OrpheusColors.neonCyan
        )
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = { voiceActions.onVoiceEnvelopeSpeedChange(voiceIndex, it) },
            color = OrpheusColors.neonCyan,
            controlId = ControlIds.voiceEnvelopeSpeed(voiceIndex)
        )
    }
}

@Composable
fun VoiceColumnSharp(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    pairIndex: Int,
    tune: Float,
    sharpness: Float,
    envSpeed: Float,
    voiceActions: VoiceActions,
) {
    Column(
        modifier =
            modifier.clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        RotaryKnob(
            value = sharpness,
            onValueChange = { voiceActions.onPairSharpnessChange(pairIndex, it) },
            label = "\u266F", // sharp
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.pairSharpness(pairIndex),
            size = 28.dp,
            progressColor = OrpheusColors.synthGreen
        )
        RotaryKnob(
            value = tune,
            onValueChange = { voiceActions.onVoiceTuneChange(voiceIndex, it) },
            label = "\u266B", // eighth notes
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.voiceTune(voiceIndex),
            size = 28.dp,
            progressColor = OrpheusColors.neonCyan
        )
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = { voiceActions.onVoiceEnvelopeSpeedChange(voiceIndex, it) },
            color = OrpheusColors.neonCyan,
            controlId = ControlIds.voiceEnvelopeSpeed(voiceIndex)
        )
    }
}

@Preview
@Composable
fun VoiceColumnModPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val voiceActions = voiceFeature.actions.toVoiceActions()

    LiquidPreviewContainerWithGradient {
        VoiceColumnMod(
            voiceIndex = 0,
            pairIndex = 0,
            tune = voiceState.voiceStates[0].tune,
            modDepth = voiceState.voiceModDepths[0],
            envSpeed = voiceState.voiceEnvelopeSpeeds[0],
            voiceActions = voiceActions,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview
@Composable
fun VoiceColumnSharpPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val voiceActions = voiceFeature.actions.toVoiceActions()

    LiquidPreviewContainerWithGradient {
        VoiceColumnSharp(
            voiceIndex = 1,
            pairIndex = 0,
            tune = voiceState.voiceStates[1].tune,
            sharpness = voiceState.pairSharpness[0],
            envSpeed = voiceState.voiceEnvelopeSpeeds[1],
            voiceActions = voiceActions,
            modifier = Modifier.padding(16.dp)
        )
    }
}
