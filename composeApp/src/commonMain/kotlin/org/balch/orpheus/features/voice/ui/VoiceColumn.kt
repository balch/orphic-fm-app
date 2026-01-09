package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.HorizontalEnvelopeSlider
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun VoiceColumnMod(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    pairIndex: Int,
    voiceState: VoiceUiState,
    voiceActions: VoiceActions,
) {
    val voiceData = voiceState.voiceStates[voiceIndex]
    val modDepth = voiceState.voiceModDepths[voiceIndex]
    val envSpeed = voiceState.voiceEnvelopeSpeeds[voiceIndex]

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
            label = "MOD",
            controlId = ControlIds.voiceFmDepth(voiceIndex),
            size = 28.dp,
            progressColor = OrpheusColors.neonMagenta
        )
        RotaryKnob(
            value = voiceData.tune,
            onValueChange = { voiceActions.onVoiceTuneChange(voiceIndex, it) },
            label = "TUNE",
            controlId = ControlIds.voiceTune(voiceIndex),
            size = 32.dp,
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
    voiceState: VoiceUiState,
    voiceActions: VoiceActions,
) {
    val voiceData = voiceState.voiceStates[voiceIndex]
    val sharpness = voiceState.pairSharpness[pairIndex]
    val envSpeed = voiceState.voiceEnvelopeSpeeds[voiceIndex]

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
            label = "SHARP",
            controlId = ControlIds.pairSharpness(pairIndex),
            size = 28.dp,
            progressColor = OrpheusColors.synthGreen
        )
        RotaryKnob(
            value = voiceData.tune,
            onValueChange = { voiceActions.onVoiceTuneChange(voiceIndex, it) },
            label = "TUNE",
            controlId = ControlIds.voiceTune(voiceIndex),
            size = 32.dp,
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
            voiceState = voiceState,
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
            voiceState = voiceState,
            voiceActions = voiceActions,
            modifier = Modifier.padding(16.dp)
        )
    }
}
