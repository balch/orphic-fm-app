package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.HorizontalEnvelopeSlider
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.patch.ModDotBox

@Composable
fun VoiceColumnMod(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    duoIndex: Int,
    tune: Float,
    duoMorph: Float,
    morphLabel: DuoKnobLabel,
    envSpeed: Float,
    voiceActions: VoiceActions,
    modulated: Set<PatchDest> = emptySet(),
) {
    Column(
        modifier =
            modifier.clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        DuoEngineKnob(
            dest = PatchDest.MORPH,
            modulated = PatchDest.MORPH in modulated,
            value = duoMorph,
            onValueChange = { voiceActions.setDuoMorph(duoIndex, it) },
            label = morphLabel,
            controlId = VoiceSymbol.duoMorph(duoIndex).controlId.key,
            color = OrpheusColors.neonMagenta,
        )
        val slot = if (voiceIndex % 2 == 0) "-A" else "-B"
        ModDotBox(PatchDest.PITCH, PatchDest.PITCH in modulated, OrpheusColors.neonCyan, tagSuffix = slot) {
            RotaryKnob(
                value = tune,
                onValueChange = { voiceActions.setVoiceTune(voiceIndex, it) },
                label = "\u266B", // eighth notes
                labelStyle = MaterialTheme.typography.labelLarge,
                controlId = VoiceSymbol.tune(voiceIndex).controlId.key,
                size = 28.dp,
                progressColor = OrpheusColors.neonCyan
            )
        }
        // Envelope Speed Slider
        ModDotBox(PatchDest.ENV, PatchDest.ENV in modulated, OrpheusColors.neonCyan, tagSuffix = slot) {
            HorizontalEnvelopeSlider(
                value = envSpeed,
                onValueChange = { voiceActions.setVoiceEnvelopeSpeed(voiceIndex, it) },
                color = OrpheusColors.neonCyan,
                controlId = VoiceSymbol.envSpeed(voiceIndex).controlId.key
            )
        }
    }
}

@Composable
fun VoiceColumnSharp(
    modifier: Modifier = Modifier,
    voiceIndex: Int,
    duoIndex: Int,
    tune: Float,
    sharpness: Float,
    sharpLabel: DuoKnobLabel,
    envSpeed: Float,
    voiceActions: VoiceActions,
    modulated: Set<PatchDest> = emptySet(),
) {
    Column(
        modifier =
            modifier.clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
                .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        DuoEngineKnob(
            dest = PatchDest.TIMBRE,
            modulated = PatchDest.TIMBRE in modulated,
            value = sharpness,
            onValueChange = { voiceActions.setDuoSharpness(duoIndex, it) },
            label = sharpLabel,
            controlId = VoiceSymbol.duoSharpness(duoIndex).controlId.key,
            color = OrpheusColors.synthGreen,
        )
        val slot = if (voiceIndex % 2 == 0) "-A" else "-B"
        ModDotBox(PatchDest.PITCH, PatchDest.PITCH in modulated, OrpheusColors.neonCyan, tagSuffix = slot) {
            RotaryKnob(
                value = tune,
                onValueChange = { voiceActions.setVoiceTune(voiceIndex, it) },
                label = "\u266B", // eighth notes
                labelStyle = MaterialTheme.typography.labelLarge,
                controlId = VoiceSymbol.tune(voiceIndex).controlId.key,
                size = 28.dp,
                progressColor = OrpheusColors.neonCyan
            )
        }
        // Envelope Speed Slider
        ModDotBox(PatchDest.ENV, PatchDest.ENV in modulated, OrpheusColors.neonCyan, tagSuffix = slot) {
            HorizontalEnvelopeSlider(
                value = envSpeed,
                onValueChange = { voiceActions.setVoiceEnvelopeSpeed(voiceIndex, it) },
                color = OrpheusColors.neonCyan,
                controlId = VoiceSymbol.envSpeed(voiceIndex).controlId.key
            )
        }
    }
}

/** A duo knob that names its job on the current engine; an inactive one shows "-" and dims. */
@Composable
private fun DuoEngineKnob(
    dest: PatchDest,
    modulated: Boolean,
    value: Float,
    onValueChange: (Float) -> Unit,
    label: DuoKnobLabel,
    controlId: String,
    color: Color,
) {
    ModDotBox(dest, modulated, color) {
        RotaryKnob(
            value = value,
            onValueChange = onValueChange,
            label = label.short,
            caption = label.glyph,
            longLabel = label.long,
            controlId = controlId,
            size = 28.dp,
            progressColor = color,
            labelColor = if (label.active) color else color.copy(alpha = 0.4f),
            modifier = Modifier.alpha(if (label.active) 1f else 0.5f),
        )
    }
}

@Preview
@Composable
fun VoiceColumnModPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsStateWithLifecycle()
    val voiceActions = voiceFeature.actions.toVoiceActions()

    LiquidPreviewContainerWithGradient {
        VoiceColumnMod(
            voiceIndex = 0,
            duoIndex = 0,
            tune = voiceState.voiceStates[0].tune,
            duoMorph = voiceState.duoMorphs[0],
            morphLabel = duoKnobLabel(OrpheusEngineId.OSC, EngineParam.MORPH, 0.5f),
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
    val voiceState by voiceFeature.stateFlow.collectAsStateWithLifecycle()
    val voiceActions = voiceFeature.actions.toVoiceActions()

    LiquidPreviewContainerWithGradient {
        VoiceColumnSharp(
            voiceIndex = 1,
            duoIndex = 0,
            tune = voiceState.voiceStates[1].tune,
            sharpness = voiceState.duoSharpness[0],
            sharpLabel = duoKnobLabel(OrpheusEngineId.SPK, EngineParam.TIMBRE, 0.5f),
            envSpeed = voiceState.voiceEnvelopeSpeeds[1],
            voiceActions = voiceActions,
            modifier = Modifier.padding(16.dp)
        )
    }
}
