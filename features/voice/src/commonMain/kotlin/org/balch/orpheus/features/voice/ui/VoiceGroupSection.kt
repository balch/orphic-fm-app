package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.triggers.DrumTriggerSource
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.ValueCycleButton

@Composable
fun VoiceGroupSection(
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    midiFeature: MidiFeature = MidiViewModel.feature(),
    quadLabel: String,
    quadColor: Color,
    voiceStartIndex: Int,
    modifier: Modifier = Modifier,
) {
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val midiState by midiFeature.stateFlow.collectAsState()
    val effects = LocalLiquidEffects.current

    val voiceActions = voiceFeature.actions.toVoiceActions()
    val midiActions = midiFeature.actions.toMidiActions()

    VoiceGroupSectionLayout(
        modifier = modifier,
        quadLabel = quadLabel,
        quadColor = quadColor,
        voiceStartIndex = voiceStartIndex,
        voiceState = voiceState,
        midiState = midiState,
        voiceActions = voiceActions,
        midiActions = midiActions,
        isVoiceBeingLearned = midiFeature.actions.isVoiceBeingLearned,
        effects = effects
    )
}

@Preview
@Composable
fun VoiceGroupSectionPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val midiFeature = MidiViewModel.previewFeature()

    LiquidPreviewContainerWithGradient {
        VoiceGroupSection(
            voiceFeature = voiceFeature,
            midiFeature = midiFeature,
            quadLabel = "QUAD 1",
            quadColor = OrpheusColors.electricBlue,
            voiceStartIndex = 0,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun VoiceGroupSectionLayout(
    modifier: Modifier = Modifier,
    quadLabel: String,
    quadColor: Color,
    voiceStartIndex: Int,
    voiceState: VoiceUiState,
    midiState: MidiUiState,
    voiceActions: VoiceActions,
    midiActions: MidiActions,
    isVoiceBeingLearned: (Int) -> Boolean,
    effects: VisualizationLiquidEffects
) {
    val liquidState = LocalLiquidState.current

    // More varied duo colors for visual interest
    val duoColors =
        if (voiceStartIndex == 0) {
            listOf(OrpheusColors.neonMagenta, OrpheusColors.electricBlue) // 1-2, 3-4
        } else {
            listOf(OrpheusColors.warmGlow, OrpheusColors.synthGreen) // 5-6, 7-8
        }

    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = quadColor,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, quadColor.copy(alpha = 0.3f), shape)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Centered Quad Header
        Text(quadLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = quadColor)

        // PITCH and HOLD centered below
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quad Index: voiceStartIndex 0 -> 0, voiceStartIndex 4 -> 1
            val quadIndex = voiceStartIndex / 4

            RotaryKnob(
                value = voiceState.quadGroupPitches[quadIndex],
                onValueChange = {
                    voiceActions.setQuadPitch(quadIndex, it)
                },
                label = "\u266B", // eighth notes
                labelStyle = MaterialTheme.typography.labelLarge,
                labelColor = quadColor,
                controlId = VoiceSymbol.quadPitch(quadIndex).controlId.key,
                size = 36.dp,
                progressColor = quadColor
            )
            RotaryKnob(
                value = voiceState.quadGroupHolds[quadIndex],
                onValueChange = {
                    voiceActions.setQuadHold(quadIndex, it)
                },
                label = "\u25AC", // tenuto
                labelStyle = MaterialTheme.typography.labelLarge,
                controlId = VoiceSymbol.quadHold(quadIndex).controlId.key,
                size = 36.dp,
                progressColor = OrpheusColors.warmGlow
            )
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Local Quad Trigger Source
                ValueCycleButton(
                    value = DrumTriggerSource.values().getOrElse(voiceState.quadTriggerSources.getOrElse(quadIndex) { 0 }) { DrumTriggerSource.INTERNAL },
                    values = DrumTriggerSource.values().toList(),
                    onValueChange = { src -> voiceActions.setQuadTriggerSource(quadIndex, src.ordinal) },
                    modifier = Modifier.height(20.dp).width(42.dp),
                    labelProvider = { src ->
                        when (src) {
                            DrumTriggerSource.INTERNAL -> "\u2022" // Bullet
                            DrumTriggerSource.FLUX_T1 -> "T1"
                            DrumTriggerSource.FLUX_T2 -> "T2"
                            DrumTriggerSource.FLUX_T3 -> "T3"
                            else -> "..."
                        }
                    }
                )
                // Envelope Mode: Gate vs Trigger
                ValueCycleButton(
                    value = if (voiceState.quadEnvelopeTriggerModes.getOrElse(quadIndex) { false }) 1 else 0,
                    values = listOf(0, 1),
                    onValueChange = { voiceActions.setQuadEnvelopeTriggerMode(quadIndex, it == 1) },
                    modifier = Modifier.height(20.dp).width(42.dp),
                    labelProvider = { mode -> if (mode == 1) "TR" else "GT" },
                    color = OrpheusColors.neonCyan
                )
            }
        }

        // Two Duo groups side by side
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DuoVoiceBox(
                voiceA = voiceStartIndex,
                voiceB = voiceStartIndex + 1,
                color = duoColors[0],
                modifier = Modifier.weight(1f).fillMaxHeight(),
                voiceStateA = voiceState.voiceStates[voiceStartIndex],
                voiceStateB = voiceState.voiceStates[voiceStartIndex + 1],
                sharpness = voiceState.pairSharpness[voiceStartIndex / 2],
                envSpeedA = voiceState.voiceEnvelopeSpeeds[voiceStartIndex],
                envSpeedB = voiceState.voiceEnvelopeSpeeds[voiceStartIndex + 1],
                duoModSource = voiceState.duoModSources[voiceStartIndex / 2],
                pairEngine = voiceState.pairEngines[voiceStartIndex / 2],
                pairHarmonics = voiceState.pairHarmonics[voiceStartIndex / 2],
                pairMorph = voiceState.pairMorphs[voiceStartIndex / 2],
                pairModDepth = voiceState.pairModDepths[voiceStartIndex / 2],
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = isVoiceBeingLearned,
                aiVoiceEngineHighlight = voiceState.aiVoiceEngineHighlights[voiceStartIndex / 2]
            )
            DuoVoiceBox(
                voiceA = voiceStartIndex + 2,
                voiceB = voiceStartIndex + 3,
                color = duoColors[1],
                modifier = Modifier.weight(1f).fillMaxHeight(),
                voiceStateA = voiceState.voiceStates[voiceStartIndex + 2],
                voiceStateB = voiceState.voiceStates[voiceStartIndex + 3],
                sharpness = voiceState.pairSharpness[(voiceStartIndex + 2) / 2],
                envSpeedA = voiceState.voiceEnvelopeSpeeds[voiceStartIndex + 2],
                envSpeedB = voiceState.voiceEnvelopeSpeeds[voiceStartIndex + 3],
                duoModSource = voiceState.duoModSources[(voiceStartIndex + 2) / 2],
                pairEngine = voiceState.pairEngines[(voiceStartIndex + 2) / 2],
                pairHarmonics = voiceState.pairHarmonics[(voiceStartIndex + 2) / 2],
                pairMorph = voiceState.pairMorphs[(voiceStartIndex + 2) / 2],
                pairModDepth = voiceState.pairModDepths[(voiceStartIndex + 2) / 2],
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = isVoiceBeingLearned,
                aiVoiceEngineHighlight = voiceState.aiVoiceEngineHighlights[(voiceStartIndex + 2) / 2]
            )
        }
    }
}

