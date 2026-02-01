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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.beats.DrumBeatsFeature
import org.balch.orpheus.features.beats.DrumBeatsViewModel
import org.balch.orpheus.features.delay.DelayFeature
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.drum.DrumFeature
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.evo.EvoFeature
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.grains.GrainsFeature
import org.balch.orpheus.features.grains.GrainsViewModel
import org.balch.orpheus.features.lfo.LfoFeature
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.looper.LooperFeature
import org.balch.orpheus.features.looper.LooperViewModel
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.resonator.ResonatorFeature
import org.balch.orpheus.features.resonator.ResonatorViewModel
import org.balch.orpheus.features.tidal.LiveCodeFeature
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.tweaks.CenterControlSection
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.features.warps.WarpsFeature
import org.balch.orpheus.features.warps.WarpsViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.HeaderFeature
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.AppTitleTreatment

/**
 * The desktop screen - injects ViewModels internally and calls Layout.
 * Use DesktopSynthScreenLayout directly for previews with mock features.
 */
@Composable
fun DesktopSynthScreen(
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    evoFeature: EvoFeature = EvoViewModel.feature(),
    headerFeature: HeaderFeature = HeaderViewModel.feature(),
    presetsFeature: PresetsFeature= PresetsViewModel.feature(),
    grainsFeature: GrainsFeature = GrainsViewModel.feature(),
    midiFeature: MidiFeature = MidiViewModel.feature(),
    vizFeature: VizFeature = VizViewModel.feature(),
    lfoFeature: LfoFeature = LfoViewModel.feature(),
    delayFeature: DelayFeature = DelayViewModel.feature(),
    distortionFeature: DistortionFeature = DistortionViewModel.feature(),
    liveCodeFeature: LiveCodeFeature = LiveCodeViewModel.feature(),
    aiOptionsFeature: AiOptionsFeature = AiOptionsViewModel.feature(),
    drumFeature: DrumFeature = DrumViewModel.feature(),
    drumBeatsFeature: DrumBeatsFeature = DrumBeatsViewModel.feature(),
    warpsFeature: WarpsFeature = WarpsViewModel.feature(),
    looperFeature: LooperFeature = LooperViewModel.feature(),
    resonatorFeature: ResonatorFeature = ResonatorViewModel.feature(),
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
    isDialogActive: Boolean = false,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester  = remember { FocusRequester() },
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
                grainsFeature = grainsFeature,
                presetsFeature = presetsFeature,
                midiFeature = midiFeature,
                vizFeature = vizFeature,
                evoFeature = evoFeature,
                lfoFeature = lfoFeature,
                delayFeature = delayFeature,
                distortionFeature = distortionFeature,
                liveCodeFeature = liveCodeFeature,
                aiOptionsFeature = aiOptionsFeature,
                drumFeature = drumFeature,
                drumBeatsFeature = drumBeatsFeature,
                warpsFeature = warpsFeature,
                resonatorFeature = resonatorFeature,
                looperFeature = looperFeature,
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

                    CenterControlSection(
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
        DesktopSynthScreen(
            voiceFeature = VoiceViewModel.previewFeature(),
            evoFeature = EvoViewModel.previewFeature(),
            headerFeature = HeaderViewModel.previewFeature(),
            grainsFeature = GrainsViewModel.previewFeature(),
            presetsFeature = PresetsViewModel.previewFeature(),
            midiFeature = MidiViewModel.previewFeature(),
            vizFeature = VizViewModel.previewFeature(),
            lfoFeature = LfoViewModel.previewFeature(),
            delayFeature = DelayViewModel.previewFeature(),
            distortionFeature = DistortionViewModel.previewFeature(),
            liveCodeFeature = LiveCodeViewModel.previewFeature(),
            aiOptionsFeature = AiOptionsViewModel.previewFeature(),
            drumFeature = DrumViewModel.previewFeature(),
            drumBeatsFeature = DrumBeatsViewModel.previewFeature(),
            warpsFeature = WarpsViewModel.previewFeature(),
            resonatorFeature = ResonatorViewModel.previewFeature(),
            looperFeature = LooperViewModel.previewFeature(),
            effects = effects,
            onDialogActiveChange = {},
            focusRequester = FocusRequester()
        )
    }
}
