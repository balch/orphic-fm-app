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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.features.ai.AiOptionsPanelActions
import org.balch.orpheus.features.ai.AiOptionsUiState
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.delay.DelayPanelActions
import org.balch.orpheus.features.delay.DelayUiState
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.distortion.DistortionPanelActions
import org.balch.orpheus.features.distortion.DistortionUiState
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.evo.EvoPanelActions
import org.balch.orpheus.features.evo.EvoUiState
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.lfo.LfoPanelActions
import org.balch.orpheus.features.lfo.LfoUiState
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.midi.MidiPanelActions
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.presets.PresetPanelActions
import org.balch.orpheus.features.presets.PresetUiState
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.stereo.StereoPanelActions
import org.balch.orpheus.features.stereo.StereoUiState
import org.balch.orpheus.features.stereo.StereoViewModel
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.tidal.ui.LiveCodeFeature
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.ui.panels.CenterControlPanel
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.HeaderPanelActions
import org.balch.orpheus.ui.panels.HeaderPanelUiState
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VizPanelActions
import org.balch.orpheus.ui.viz.VizUiState
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

/**
 * The desktop screen - injects ViewModels internally and calls Layout.
 * Use DesktopSynthScreenLayout directly for previews with mock features.
 */
@Composable
fun DesktopSynthScreen(
    isDialogActive: Boolean,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
) {
    // Inject all ViewModels internally via their factory methods
    val voiceFeature = VoiceViewModel.panelFeature()
    val evoFeature = EvoViewModel.panelFeature()
    val headerFeature = HeaderViewModel.panelFeature()
    val presetsFeature = PresetsViewModel.panelFeature()
    val midiFeature = MidiViewModel.panelFeature()
    val stereoFeature = StereoViewModel.panelFeature()
    val vizFeature = VizViewModel.panelFeature()
    val lfoFeature = LfoViewModel.panelFeature()
    val delayFeature = DelayViewModel.panelFeature()
    val distortionFeature = DistortionViewModel.panelFeature()
    val liveCodeFeature = LiveCodeViewModel.panelFeature()
    val aiOptionsFeature = AiOptionsViewModel.panelFeature()

    DesktopSynthScreenLayout(
        voiceFeature = voiceFeature,
        evoFeature = evoFeature,
        headerFeature = headerFeature,
        presetsFeature = presetsFeature,
        midiFeature = midiFeature,
        stereoFeature = stereoFeature,
        vizFeature = vizFeature,
        lfoFeature = lfoFeature,
        delayFeature = delayFeature,
        distortionFeature = distortionFeature,
        liveCodeFeature = liveCodeFeature,
        aiOptionsFeature = aiOptionsFeature,
        effects = LocalLiquidEffects.current,
        isDialogActive = isDialogActive,
        onDialogActiveChange = onDialogActiveChange,
        focusRequester = focusRequester,
    )
}

/**
 * Layout function for previews - uses Layout composables that accept state/actions
 */
@Composable
fun DesktopSynthScreenLayout(
    voiceFeature: SynthFeature<VoiceUiState, VoicePanelActions>,
    evoFeature: SynthFeature<EvoUiState, EvoPanelActions>,
    headerFeature: SynthFeature<HeaderPanelUiState, HeaderPanelActions>,
    presetsFeature: SynthFeature<PresetUiState, PresetPanelActions>,
    midiFeature: SynthFeature<MidiUiState, MidiPanelActions>,
    stereoFeature: SynthFeature<StereoUiState, StereoPanelActions>,
    vizFeature: SynthFeature<VizUiState, VizPanelActions>,
    lfoFeature: SynthFeature<LfoUiState, LfoPanelActions>,
    delayFeature: SynthFeature<DelayUiState, DelayPanelActions>,
    distortionFeature: SynthFeature<DistortionUiState, DistortionPanelActions>,
    liveCodeFeature: LiveCodeFeature,
    aiOptionsFeature: SynthFeature<AiOptionsUiState, AiOptionsPanelActions>,
    effects: VisualizationLiquidEffects,
    isDialogActive: Boolean = false,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        voiceFeature = voiceFeature,
                        isDialogActive = isDialogActive
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Header panel
            HeaderPanel(
                headerFeature = headerFeature,
                presetsFeature = presetsFeature,
                midiFeature = midiFeature,
                stereoFeature = stereoFeature,
                vizFeature = vizFeature,
                evoFeature = evoFeature,
                lfoFeature = lfoFeature,
                delayFeature = delayFeature,
                distortionFeature = distortionFeature,
                liveCodeFeature = liveCodeFeature,
                aiOptionsFeature = aiOptionsFeature,
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
                        midiFeature = midiFeature,
                        quadLabel = "1-4",
                        quadColor = OrpheusColors.neonMagenta,
                        voiceStartIndex = 0,
                        modifier = Modifier.weight(1f),
                    )

                    CenterControlPanel(
                        voiceFeature = voiceFeature,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.4f)
                    )

                    VoiceGroupSection(
                        voiceFeature = voiceFeature,
                        midiFeature = midiFeature,
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

// ==================== PREVIEWS ====================

@Preview(widthDp = 1200, heightDp = 800)
@Composable
private fun DesktopSynthScreenPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
        DesktopSynthScreenLayout(
            voiceFeature = VoiceViewModel.previewFeature(),
            evoFeature = EvoViewModel.previewFeature(),
            headerFeature = HeaderViewModel.previewFeature(),
            presetsFeature = PresetsViewModel.previewFeature(),
            midiFeature = MidiViewModel.previewFeature(),
            stereoFeature = StereoViewModel.previewFeature(),
            vizFeature = VizViewModel.previewFeature(),
            lfoFeature = LfoViewModel.previewFeature(),
            delayFeature = DelayViewModel.previewFeature(),
            distortionFeature = DistortionViewModel.previewFeature(),
            liveCodeFeature = LiveCodeViewModel.previewFeature(),
            aiOptionsFeature = AiOptionsViewModel.previewFeature(),
            effects = effects,
            onDialogActiveChange = {},
            focusRequester = FocusRequester()
        )
    }
}
