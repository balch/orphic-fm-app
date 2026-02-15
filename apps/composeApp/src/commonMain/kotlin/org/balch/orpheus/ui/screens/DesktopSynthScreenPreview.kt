package org.balch.orpheus.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.features.ai.AiOptionsPanelRegistration
import org.balch.orpheus.features.beats.BeatsPanelRegistration
import org.balch.orpheus.features.delay.DelayPanelRegistration
import org.balch.orpheus.features.distortion.DistortionPanelRegistration
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.drum.DrumsPanelRegistration
import org.balch.orpheus.features.evo.EvoPanelRegistration
import org.balch.orpheus.features.flux.FluxPanelRegistration
import org.balch.orpheus.features.flux.TriggerRouterPanelRegistration
import org.balch.orpheus.features.grains.GrainsPanelRegistration
import org.balch.orpheus.features.lfo.LfoPanelRegistration
import org.balch.orpheus.features.looper.LooperPanelRegistration
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiPanelActions
import org.balch.orpheus.features.midi.MidiPanelRegistration
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.presets.PresetsPanelRegistration
import org.balch.orpheus.features.resonator.ResonatorPanelRegistration
import org.balch.orpheus.features.reverb.ReverbPanelRegistration
import org.balch.orpheus.features.speech.SpeechPanelRegistration
import org.balch.orpheus.features.speech.SpeechViewModel
import org.balch.orpheus.features.tidal.LiveCodePanelRegistration
import org.balch.orpheus.features.visualizations.VizPanelRegistration
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.warps.WarpsPanelRegistration
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient

private fun previewPanels(): List<FeaturePanel> = listOf(
    PresetsPanelRegistration.preview(),
    DistortionPanelRegistration.preview(),
    EvoPanelRegistration.preview(),
    MidiPanelRegistration.preview(),
    VizPanelRegistration.preview(),
    LfoPanelRegistration.preview(),
    DelayPanelRegistration.preview(),
    ReverbPanelRegistration.preview(),
    ResonatorPanelRegistration.preview(),
    GrainsPanelRegistration.preview(),
    WarpsPanelRegistration.preview(),
    TriggerRouterPanelRegistration.preview(),
    FluxPanelRegistration.preview(),
    LooperPanelRegistration.preview(),
    LiveCodePanelRegistration.preview(),
    SpeechPanelRegistration.preview(),
    DrumsPanelRegistration.preview(),
    BeatsPanelRegistration.preview(),
    AiOptionsPanelRegistration.preview(),
)

@Preview(widthDp = 1200, heightDp = 800)
@Composable
private fun DesktopSynthScreenPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    val panels = previewPanels()
    val features: Set<SynthFeature<*, *>> = setOf(
        HeaderViewModel.previewFeature(panels = panels),
        VoiceViewModel.previewFeature(),
        DrumViewModel.previewFeature(),
        SpeechViewModel.previewFeature(),
        MidiViewModel.previewFeature(),
    )
    LiquidPreviewContainerWithGradient(
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
        DesktopSynthScreen(
            features = features,
            effects = effects,
            onDialogActiveChange = {},
            focusRequester = remember { FocusRequester() }
        )
    }
}
