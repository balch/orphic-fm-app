package org.balch.orpheus.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Provider
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.features.ai.AiOptionsPanelRegistration
import org.balch.orpheus.features.beats.BeatsPanelRegistration
import org.balch.orpheus.features.delay.DelayPanelRegistration
import org.balch.orpheus.features.distortion.DistortionPanelRegistration
import org.balch.orpheus.features.drum.DrumFeature
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
import org.balch.orpheus.features.speech.SpeechFeature
import org.balch.orpheus.features.speech.SpeechPanelRegistration
import org.balch.orpheus.features.speech.SpeechViewModel
import org.balch.orpheus.features.tidal.LiveCodePanelRegistration
import org.balch.orpheus.features.tweaks.CenterControlSection
import org.balch.orpheus.features.visualizations.VizPanelRegistration
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.features.warps.WarpsPanelRegistration
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.HeaderFeature
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import kotlin.reflect.KClass

// A minimal mock MidiViewModel that implements the ViewModel interface.
private class MockMidiViewModel : ViewModel(), MidiFeature {
    override val stateFlow = MutableStateFlow(MidiUiState(isConnected = true, deviceName = "Mock Device"))
    override val actions = MidiPanelActions(
        toggleLearnMode = {},
        saveLearnedMappings = {},
        cancelLearnMode = {},
        selectControlForLearning = {},
        selectVoiceForLearning = {},
        isControlBeingLearned = { false },
        isVoiceBeingLearned = { false }
    )
}

// Mock MetroViewModelFactory for previews to satisfy CompositionLocalProvider requirement.
// This factory provides a minimal mock MidiViewModel when requested by the metroViewModel() helper,
// preventing a "No MetroViewModelFactory registered" error during preview rendering.
private class PreviewMetroViewModelFactory : MetroViewModelFactory() {
    override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>
        get() = mapOf(
            MidiViewModel::class to Provider { MockMidiViewModel() as ViewModel }
        )
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>>
        get() = emptyMap()
    override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>>
        get() = emptyMap()
}

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
    // Provide a mock MetroViewModelFactory for the preview environment.
    // This is necessary because some components within DesktopSynthScreen (e.g., MidiViewModel.feature())
    // expect a MetroViewModelFactory to be available via CompositionLocal, even when
    // using preview-specific features.
    CompositionLocalProvider(LocalMetroViewModelFactory provides PreviewMetroViewModelFactory()) {
        LiquidPreviewContainerWithGradient(
            effects = effects,
            modifier = Modifier.fillMaxSize()
        ) {
            DesktopSynthScreen(
                headerFeature = HeaderViewModel.previewFeature(panels = panels),
                panels = panels,
                voiceFeature = VoiceViewModel.previewFeature(),
                drumFeature = DrumViewModel.previewFeature(),
                speechFeature = SpeechViewModel.previewFeature(),
                midiFeature = MidiViewModel.previewFeature(), // Pass the preview MidiFeature
                effects = effects,
                onDialogActiveChange = {},
                focusRequester = FocusRequester()
            )
        }
    }
}
