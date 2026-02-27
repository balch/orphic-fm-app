package org.balch.orpheus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.LeftPanelMode
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors

@Preview(widthDp = 1200, heightDp = 800)
@Composable
private fun DesktopSynthScreenPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
        DesktopSynthScreen(
            effects = effects,
            onDialogActiveChange = {},
            focusRequester = remember { FocusRequester() }
        )
    }
}

@Preview(widthDp = 800, heightDp = 500, name = "Bender+Strings vs Voices")
@Composable
private fun DesktopBenderStringsVsVoicesPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val midiFeature = MidiViewModel.previewFeature()

    LiquidPreviewContainerWithGradient {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DesktopBenderStringsSection(
                voiceState = VoiceUiState(selectedLeftPanel = LeftPanelMode.BENDER_STRINGS),
                actions = VoicePanelActions.EMPTY,
                onToggle = {},
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            VoiceGroupSection(
                voiceFeature = voiceFeature,
                midiFeature = midiFeature,
                quadLabel = "5-8",
                quadColor = OrpheusColors.synthGreen,
                voiceStartIndex = 4,
                showQuadToggle = true,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}
