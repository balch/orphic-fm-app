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
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.HorizontalEnvelopeSlider
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun VoiceColumnMod(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    duoIndex: Int,
    tune: Float,
    duoMorph: Float,
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
            value = duoMorph,
            onValueChange = { voiceActions.setDuoMorph(duoIndex, it) },
            label = "\u03C8",
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = VoiceSymbol.duoMorph(duoIndex).controlId.key,
            size = 28.dp,
            progressColor = OrpheusColors.neonMagenta
        )
        RotaryKnob(
            value = tune,
            onValueChange = { voiceActions.setVoiceTune(voiceIndex, it) },
            label = "\u266B", // eighth notes
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = VoiceSymbol.tune(voiceIndex).controlId.key,
            size = 28.dp,
            progressColor = OrpheusColors.neonCyan
        )
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = { voiceActions.setVoiceEnvelopeSpeed(voiceIndex, it) },
            color = OrpheusColors.neonCyan,
            controlId = VoiceSymbol.envSpeed(voiceIndex).controlId.key
        )
    }
}

@Composable
fun VoiceColumnSharp(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    duoIndex: Int,
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
            onValueChange = { voiceActions.setDuoSharpness(duoIndex, it) },
            label = "\u266F", // sharp
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = VoiceSymbol.duoSharpness(duoIndex).controlId.key,
            size = 28.dp,
            progressColor = OrpheusColors.synthGreen
        )
        RotaryKnob(
            value = tune,
            onValueChange = { voiceActions.setVoiceTune(voiceIndex, it) },
            label = "\u266B", // eighth notes
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = VoiceSymbol.tune(voiceIndex).controlId.key,
            size = 28.dp,
            progressColor = OrpheusColors.neonCyan
        )
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = { voiceActions.setVoiceEnvelopeSpeed(voiceIndex, it) },
            color = OrpheusColors.neonCyan,
            controlId = VoiceSymbol.envSpeed(voiceIndex).controlId.key
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
            duoIndex = 0,
            tune = voiceState.voiceStates[0].tune,
            duoMorph = voiceState.duoMorphs[0],
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
            duoIndex = 0,
            tune = voiceState.voiceStates[1].tune,
            sharpness = voiceState.duoSharpness[0],
            envSpeed = voiceState.voiceEnvelopeSpeeds[1],
            voiceActions = voiceActions,
            modifier = Modifier.padding(16.dp)
        )
    }
}
