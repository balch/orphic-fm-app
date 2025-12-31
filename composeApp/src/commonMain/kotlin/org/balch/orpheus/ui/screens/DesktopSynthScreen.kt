package org.balch.orpheus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.midi.MidiPanelActions
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.ui.MidiActions
import org.balch.orpheus.features.voice.ui.VoiceActions
import org.balch.orpheus.features.voice.ui.VoiceGroupSectionLayout
import org.balch.orpheus.ui.panels.CenterControlPanelLayout
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper
import org.balch.orpheus.ui.utils.rememberPanelState
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The desktop screen
 */
@Composable
fun DesktopSynthScreen(
    voiceViewModel: VoiceViewModel = metroViewModel(),
    midiViewModel: MidiViewModel = metroViewModel(),
    evoViewModel: EvoViewModel = metroViewModel(),
    isDialogActive: Boolean,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    isVizInitiallyExpanded: Boolean = true
) {
    val voice = rememberPanelState(voiceViewModel)
    val midi = rememberPanelState(midiViewModel)
    val evo = rememberPanelState(evoViewModel) // Not strictly needed for layout logic, but consistent
    
    val effects = LocalLiquidEffects.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        voiceState = voice.state,
                        voiceViewModel = voiceViewModel,
                        isDialogActive = isDialogActive
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            DesktopSynthScreenLayout(
                voiceFeature = voice,
                midiFeature = midi,
                effects = effects,
                headerContent = {
                    HeaderPanel(
                        onDialogActiveChange = onDialogActiveChange,
                        isVizInitiallyExpanded = isVizInitiallyExpanded,
                        evoViewModel = evoViewModel
                    )
                }
            )
        }
    }
}

@Composable
fun ColumnScope.DesktopSynthScreenLayout(
    voiceFeature: ViewModelStateActionMapper<VoiceUiState, VoicePanelActions>,
    midiFeature: ViewModelStateActionMapper<MidiUiState, MidiPanelActions>,
    effects: VisualizationLiquidEffects,
    headerContent: @Composable () -> Unit
) {
    val voiceState = voiceFeature.state
    val voicePanelActions = voiceFeature.actions
    val midiState = midiFeature.state
    val midiPanelActions = midiFeature.actions

    // Create adapters for legacy subcomponents
    val voiceActions = remember(voicePanelActions) {
        object : VoiceActions {
            override fun onDuoModSourceChange(pairIndex: Int, source: ModSource) = voicePanelActions.onDuoModSourceChange(pairIndex, source)
            override fun onVoiceTuneChange(index: Int, value: Float) = voicePanelActions.onVoiceTuneChange(index, value)
            override fun onDuoModDepthChange(pairIndex: Int, value: Float) = voicePanelActions.onDuoModDepthChange(pairIndex, value)
            override fun onVoiceEnvelopeSpeedChange(index: Int, value: Float) = voicePanelActions.onVoiceEnvelopeSpeedChange(index, value)
            override fun onHoldChange(index: Int, holding: Boolean) = voicePanelActions.onHoldChange(index, holding)
            override fun onPulseStart(index: Int) = voicePanelActions.onPulseStart(index)
            override fun onPulseEnd(index: Int) = voicePanelActions.onPulseEnd(index)
            override fun onPairSharpnessChange(pairIndex: Int, value: Float) = voicePanelActions.onPairSharpnessChange(pairIndex, value)
            override fun onQuadPitchChange(quadIndex: Int, value: Float) = voicePanelActions.onQuadPitchChange(quadIndex, value)
            override fun onQuadHoldChange(quadIndex: Int, value: Float) = voicePanelActions.onQuadHoldChange(quadIndex, value)
            override fun onFmStructureChange(crossQuad: Boolean) = voicePanelActions.onFmStructureChange(crossQuad)
            override fun onTotalFeedbackChange(value: Float) = voicePanelActions.onTotalFeedbackChange(value)
            override fun onVibratoChange(value: Float) = voicePanelActions.onVibratoChange(value)
            override fun onVoiceCouplingChange(value: Float) = voicePanelActions.onVoiceCouplingChange(value)
            override fun onDialogActiveChange(active: Boolean) { /* handled by parent */ }
            override fun onWobblePulseStart(index: Int, x: Float, y: Float) = voicePanelActions.onWobblePulseStart(index, x, y)
            override fun onWobbleMove(index: Int, x: Float, y: Float) = voicePanelActions.onWobbleMove(index, x, y)
            override fun onWobblePulseEnd(index: Int) = voicePanelActions.onWobblePulseEnd(index)
        }
    }

    val midiActions = remember(midiPanelActions) {
        object : MidiActions {
            override fun selectVoiceForLearning(voiceIndex: Int) = midiPanelActions.onSelectVoiceForLearning(voiceIndex)
        }
    }

    // Top panel row
    headerContent()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        // Main section: Voice groups + center controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            VoiceGroupSectionLayout(
                quadLabel = "1-4",
                quadColor = OrpheusColors.neonMagenta,
                voiceStartIndex = 0,
                modifier = Modifier.weight(1f),
                voiceState = voiceState,
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = midiPanelActions.isVoiceBeingLearned,
                effects = effects
            )

            CenterControlPanelLayout(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.4f),
                voiceState = voiceState,
                effects = effects,
                onVibratoChange = voiceActions::onVibratoChange,
                onVoiceCouplingChange = voiceActions::onVoiceCouplingChange,
                onTotalFeedbackChange = voiceActions::onTotalFeedbackChange,
                onFmStructureChange = voiceActions::onFmStructureChange
            )

            VoiceGroupSectionLayout(
                quadLabel = "5-8",
                quadColor = OrpheusColors.synthGreen,
                voiceStartIndex = 4,
                modifier = Modifier.weight(1f),
                voiceState = voiceState,
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = midiPanelActions.isVoiceBeingLearned,
                effects = effects
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

@Preview(widthDp = 800, heightDp = 600)
@Composable
fun DesktopSynthScreenPreview() {
    Box(modifier = Modifier.background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DesktopSynthScreenLayout(
                voiceFeature = VoiceViewModel.PREVIEW,
                midiFeature = MidiViewModel.PREVIEW,
                effects = LocalLiquidEffects.current,
                headerContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Header Panel Placeholder", color = Color.White)
                    }
                }
            )
        }
    }
}
