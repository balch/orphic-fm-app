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
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.delay.DelayFeature
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.evo.EvoFeature
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.lfo.LfoFeature
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.stereo.StereoFeature
import org.balch.orpheus.features.stereo.StereoViewModel
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.tidal.ui.LiveCodeFeature
import org.balch.orpheus.features.viz.VizFeature
import org.balch.orpheus.features.viz.VizViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.ui.panels.CenterControlPanel
import org.balch.orpheus.ui.panels.HeaderFeature
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
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
    val voiceFeature = VoiceViewModel.feature()
    val evoFeature = EvoViewModel.feature()
    val headerFeature = HeaderViewModel.feature()
    val presetsFeature = PresetsViewModel.feature()
    val midiFeature = MidiViewModel.feature()
    val stereoFeature = StereoViewModel.feature()
    val vizFeature = VizViewModel.feature()
    val lfoFeature = LfoViewModel.feature()
    val delayFeature = DelayViewModel.feature()
    val distortionFeature = DistortionViewModel.feature()
    val liveCodeFeature = LiveCodeViewModel.feature()
    val aiOptionsFeature = AiOptionsViewModel.feature()

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
    voiceFeature: VoicesFeature,
    evoFeature: EvoFeature,
    headerFeature: HeaderFeature,
    presetsFeature: PresetsFeature,
    midiFeature: MidiFeature,
    stereoFeature: StereoFeature,
    vizFeature: VizFeature,
    lfoFeature: LfoFeature,
    delayFeature: DelayFeature,
    distortionFeature: DistortionFeature,
    liveCodeFeature: LiveCodeFeature,
    aiOptionsFeature: AiOptionsFeature,
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
