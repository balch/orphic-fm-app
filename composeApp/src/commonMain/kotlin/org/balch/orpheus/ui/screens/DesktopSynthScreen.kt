package org.balch.orpheus.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.ui.MidiActions
import org.balch.orpheus.features.voice.ui.VoiceActions
import org.balch.orpheus.features.voice.ui.VoiceGroupSectionLayout
import org.balch.orpheus.ui.panels.CenterControlPanelLayout
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The desktop screen
 */
@Composable
fun DesktopSynthScreen(
    voiceViewModel: VoiceViewModel = metroViewModel(),
    midiViewModel: MidiViewModel = metroViewModel(),
    isDialogActive: Boolean,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    isVizInitiallyExpanded: Boolean = true
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val midiState by midiViewModel.uiState.collectAsState()
    val effects = LocalLiquidEffects.current

    val voiceActions = object : VoiceActions {
        override fun onDuoModSourceChange(pairIndex: Int, source: ModSource) = voiceViewModel.onDuoModSourceChange(pairIndex, source)
        override fun onVoiceTuneChange(index: Int, value: Float) = voiceViewModel.onVoiceTuneChange(index, value)
        override fun onDuoModDepthChange(pairIndex: Int, value: Float) = voiceViewModel.onDuoModDepthChange(pairIndex, value)
        override fun onVoiceEnvelopeSpeedChange(index: Int, value: Float) = voiceViewModel.onVoiceEnvelopeSpeedChange(index, value)
        override fun onHoldChange(index: Int, holding: Boolean) = voiceViewModel.onHoldChange(index, holding)
        override fun onPulseStart(index: Int) = voiceViewModel.onPulseStart(index)
        override fun onPulseEnd(index: Int) = voiceViewModel.onPulseEnd(index)
        override fun onPairSharpnessChange(pairIndex: Int, value: Float) = voiceViewModel.onPairSharpnessChange(pairIndex, value)
        override fun onQuadPitchChange(quadIndex: Int, value: Float) = voiceViewModel.onQuadPitchChange(quadIndex, value)
        override fun onQuadHoldChange(quadIndex: Int, value: Float) = voiceViewModel.onQuadHoldChange(quadIndex, value)
        override fun onFmStructureChange(crossQuad: Boolean) = voiceViewModel.onFmStructureChange(crossQuad)
        override fun onTotalFeedbackChange(value: Float) = voiceViewModel.onTotalFeedbackChange(value)
        override fun onVibratoChange(value: Float) = voiceViewModel.onVibratoChange(value)
        override fun onVoiceCouplingChange(value: Float) = voiceViewModel.onVoiceCouplingChange(value)
        override fun onDialogActiveChange(active: Boolean) { /* handled by parent */ }
    }

    val midiActions = object : MidiActions {
        override fun selectVoiceForLearning(voiceIndex: Int) = midiViewModel.selectVoiceForLearning(voiceIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        voiceState = voiceState,
                        voiceViewModel = voiceViewModel,
                        isDialogActive = isDialogActive
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            DesktopSynthScreenLayout(
                voiceState = voiceState,
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = midiViewModel::isVoiceBeingLearned,
                effects = effects,
                headerContent = {
                    HeaderPanel(
                        onDialogActiveChange = onDialogActiveChange,
                        isVizInitiallyExpanded = isVizInitiallyExpanded
                    )
                }
            )
        }
    }
}

@Composable
fun ColumnScope.DesktopSynthScreenLayout(
    voiceState: VoiceUiState,
    midiState: MidiUiState,
    voiceActions: VoiceActions,
    midiActions: MidiActions,
    isVoiceBeingLearned: (Int) -> Boolean,
    effects: VisualizationLiquidEffects,
    headerContent: @Composable () -> Unit
) {
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
                isVoiceBeingLearned = isVoiceBeingLearned,
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
                isVoiceBeingLearned = isVoiceBeingLearned,
                effects = effects
            )
        }

        val liquidState = LocalLiquidState.current
        val shape = RoundedCornerShape(8.dp)
        val density = LocalDensity.current
        val textShadow = remember(effects.title.titleElevation, density) {
            val blur = with(density) { effects.title.titleElevation.toPx() }
            if (blur > 0f) {
                Shadow(
                    color = Color.Black.copy(alpha = 0.5f),
                    offset = Offset(0f, blur / 2),
                    blurRadius = blur
                )
            } else {
                Shadow.None
            }
        }

        Card(
            modifier = Modifier
                .padding(top = 20.dp)
                .align(Alignment.TopCenter)
                .liquidVizEffects(
                    liquidState = liquidState,
                    scope = effects.title.scope,
                    frostAmount = effects.frostLarge.dp,
                    color = OrpheusColors.softPurple,
                    tintAlpha = 0.2f,
                    shape = shape,
                ),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = effects.title.titleElevation),
            shape = shape,
            border = BorderStroke(effects.title.borderWidth, effects.title.borderColor)
        ) {
            Text(
                text = "ORPHEUS-8",
                fontSize = effects.title.titleSize,
                fontWeight = FontWeight.Bold,
                color = effects.title.titleColor,
                style = TextStyle(shadow = textShadow),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)

            )
        }

    }
}

@Preview(widthDp = 800, heightDp = 600)
@Composable
fun DesktopSynthScreenPreview() {
    val voiceActions = object : VoiceActions {
        override fun onDuoModSourceChange(pairIndex: Int, source: ModSource) {}
        override fun onVoiceTuneChange(index: Int, value: Float) {}
        override fun onDuoModDepthChange(pairIndex: Int, value: Float) {}
        override fun onVoiceEnvelopeSpeedChange(index: Int, value: Float) {}
        override fun onHoldChange(index: Int, holding: Boolean) {}
        override fun onPulseStart(index: Int) {}
        override fun onPulseEnd(index: Int) {}
        override fun onPairSharpnessChange(pairIndex: Int, value: Float) {}
        override fun onQuadPitchChange(quadIndex: Int, value: Float) {}
        override fun onQuadHoldChange(quadIndex: Int, value: Float) {}
        override fun onFmStructureChange(crossQuad: Boolean) {}
        override fun onTotalFeedbackChange(value: Float) {}
        override fun onVibratoChange(value: Float) {}
        override fun onVoiceCouplingChange(value: Float) {}
        override fun onDialogActiveChange(active: Boolean) {}
    }

    val midiActions = object : MidiActions {
        override fun selectVoiceForLearning(voiceIndex: Int) {}
    }

    Box(modifier = Modifier.background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DesktopSynthScreenLayout(
                voiceState = VoiceUiState(),
                midiState = MidiUiState(),
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = { false },
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
