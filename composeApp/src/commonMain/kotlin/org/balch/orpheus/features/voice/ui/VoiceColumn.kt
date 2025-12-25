package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.HorizontalEnvelopeSlider
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun VoiceColumnMod(
    num: Int,
    voiceIndex: Int,
    pairIndex: Int, // Kept for API compatibility, though not used directly in this implementation
    tune: Float,
    onTuneChange: (Float) -> Unit,
    modDepth: Float,
    onModDepthChange: (Float) -> Unit,
    envSpeed: Float,
    onEnvSpeedChange: (Float) -> Unit
) {
    Column(
        modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = OrpheusColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(
            value = modDepth,
            onValueChange = onModDepthChange,
            label = "MOD",
            controlId = ControlIds.voiceFmDepth(voiceIndex),
            size = 24.dp,
            progressColor = OrpheusColors.neonMagenta
        )
        RotaryKnob(
            value = tune,
            onValueChange = onTuneChange,
            label = "TUNE",
            controlId = ControlIds.voiceTune(voiceIndex),
            size = 28.dp,
            progressColor = OrpheusColors.neonCyan
        )
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = onEnvSpeedChange,
            color = OrpheusColors.neonCyan,
            controlId = ControlIds.voiceEnvelopeSpeed(voiceIndex)
        )
    }
}

@Composable
fun VoiceColumnSharp(
    num: Int,
    voiceIndex: Int,
    pairIndex: Int,
    tune: Float,
    onTuneChange: (Float) -> Unit,
    sharpness: Float,
    onSharpnessChange: (Float) -> Unit,
    envSpeed: Float,
    onEnvSpeedChange: (Float) -> Unit
) {
    Column(
        modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = OrpheusColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(
            value = sharpness,
            onValueChange = onSharpnessChange,
            label = "SHARP",
            controlId = ControlIds.pairSharpness(pairIndex),
            size = 24.dp,
            progressColor = OrpheusColors.synthGreen
        )
        RotaryKnob(
            value = tune,
            onValueChange = onTuneChange,
            label = "TUNE",
            controlId = ControlIds.voiceTune(voiceIndex),
            size = 28.dp,
            progressColor = OrpheusColors.neonCyan
        )
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = onEnvSpeedChange,
            color = OrpheusColors.neonCyan,
            controlId = ControlIds.voiceEnvelopeSpeed(voiceIndex)
        )
    }
}
