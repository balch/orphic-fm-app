package org.balch.orpheus.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient

@Preview(widthDp = 1200, heightDp = 800)
@Composable
private fun DesktopSynthScreenPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
        DesktopSynthLayout(
            headerFeature = HeaderViewModel.previewFeature(),
            voiceFeature = VoiceViewModel.previewFeature(),
            midiFeature = MidiViewModel.previewFeature(),
            keyActions = emptyMap(),
            effects = effects,
            onDialogActiveChange = {},
            focusRequester = remember { FocusRequester() },
        )
    }
}
