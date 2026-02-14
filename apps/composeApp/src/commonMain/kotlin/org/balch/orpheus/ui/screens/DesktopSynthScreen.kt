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

/**
 * The desktop screen - injects ViewModels internally and calls Layout.
 * Use DesktopSynthScreenLayout directly for previews with mock features.
 */
@Composable
fun DesktopSynthScreen(
    headerFeature: HeaderFeature = HeaderViewModel.feature(),
    panels: List<FeaturePanel> = headerFeature.visiblePanels,
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    drumFeature: DrumFeature = DrumViewModel.feature(),
    speechFeature: SpeechFeature = SpeechViewModel.feature(),
    midiFeature: MidiFeature = MidiViewModel.feature(), // Added midiFeature parameter
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
    isDialogActive: Boolean = false,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    // Request focus for keyboard input handling
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Spacebar trigger for TTS (when enabled and not editing text)
                    if (!isDialogActive &&
                        event.key == Key.Spacebar &&
                        event.type == KeyEventType.KeyDown &&
                        speechFeature.stateFlow.value.spacebarTrigger
                    ) {
                        val state = speechFeature.stateFlow.value
                        if (state.isSpeaking || state.isGenerating) {
                            speechFeature.actions.stop()
                        } else {
                            speechFeature.actions.speak()
                        }
                        return@onPreviewKeyEvent true
                    }

                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        voiceFeature = voiceFeature,
                        drumFeature = drumFeature,
                        isDialogActive = isDialogActive
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Header panel
            HeaderPanel(
                modifier = Modifier.fillMaxWidth()
                    .weight(0.75f),
                headerFeature = headerFeature,
                panels = panels,
                onDialogActiveChange = onDialogActiveChange,
            )

            // Main content area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Voice groups + center controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    VoiceGroupSection(
                        voiceFeature = voiceFeature,
                        midiFeature = midiFeature, // Pass midiFeature to VoiceGroupSection
                        quadLabel = "1-4",
                        quadColor = OrpheusColors.neonMagenta,
                        voiceStartIndex = 0,
                        modifier = Modifier.weight(1f),
                    )

                    CenterControlSection(
                        voiceFeature = voiceFeature,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.4f)
                    )

                    VoiceGroupSection(
                        voiceFeature = voiceFeature,
                        midiFeature = midiFeature, // Pass midiFeature to VoiceGroupSection
                        quadLabel = "5-8",
                        quadColor = OrpheusColors.synthGreen,
                        voiceStartIndex = 4,
                        modifier = Modifier.weight(1f),
                    )
                }

                AppTitleTreatment(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .align(Alignment.TopCenter),
                    effects = effects,
                )
            }
        }
    }
}