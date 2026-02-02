package org.balch.orpheus.features.tweaks

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.CrossModSelector
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun CenterControlSection(
    voiceFeature: SynthFeature<VoiceUiState, VoicePanelActions>,
    modifier: Modifier = Modifier,
) {
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val actions = voiceFeature.actions
    val effects = LocalLiquidEffects.current
    val liquidState = LocalLiquidState.current
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.electricBlue,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Spacer(modifier = Modifier.height(80.dp))
        CrossModSelector(
            isCrossQuad = voiceState.fmStructureCrossQuad,
            onToggle = actions.setFmStructure
        )
        Spacer(modifier = Modifier.weight(.1f))
        RotaryKnob(
            value = voiceState.totalFeedback,
            onValueChange = actions.setTotalFeedback,
            label = "\u221E\u221E", // infinity",
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.TOTAL_FEEDBACK,
            size = 32.dp,
            progressColor = OrpheusColors.neonCyan
        )
        Spacer(modifier = Modifier.weight(.1f))
        RotaryKnob(
            value = voiceState.vibrato,
            onValueChange = actions.setVibrato,
            label = "\u2307",
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.VIBRATO,
            size = 32.dp,
            progressColor = OrpheusColors.neonMagenta
        )
        Spacer(modifier = Modifier.weight(.1f))
        RotaryKnob(
            value = voiceState.voiceCoupling,
            onValueChange = actions.setVoiceCoupling,
            label = "\u2A1D",  // join
            labelStyle = MaterialTheme.typography.labelLarge,
            controlId = ControlIds.VOICE_COUPLING,
            size = 32.dp,
            progressColor = OrpheusColors.warmGlow
        )
        Spacer(modifier = Modifier.weight(.2f))
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(heightDp = 360)
@Composable
fun CenterControlPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        CenterControlSection(
            voiceFeature = VoiceViewModel.previewFeature()
        )
    }
}
