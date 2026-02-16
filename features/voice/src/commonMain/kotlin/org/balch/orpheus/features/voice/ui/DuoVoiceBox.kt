package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.EnginePickerButton
import org.balch.orpheus.ui.widgets.ModFaderSelector
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.engineLabel

@Composable
fun DuoVoiceBox(
    modifier: Modifier = Modifier,
    voiceA: Int,
    voiceB: Int,
    color: Color,
    voiceStateA: VoiceState,
    voiceStateB: VoiceState,
    sharpness: Float,
    envSpeedA: Float,
    envSpeedB: Float,
    duoModSource: ModSource,
    duoEngine: Int,
    duoHarmonics: Float,
    duoMorph: Float,
    duoModSourceLevel: Float,
    midiState: MidiUiState,
    voiceActions: VoiceActions,
    midiActions: MidiActions,
    isVoiceBeingLearned: (Int) -> Boolean,
    aiVoiceEngineHighlight: Boolean = false
) {
    Column(
        modifier =
            modifier.widthIn(min = 100.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))  // Brighter border
                .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Full-width header bar with Duo label, mod depth, and mod source selector
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.08f))  // Reduced for transparency
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${voiceA + 1}-${voiceB + 1}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )

            val duoIndex = voiceA / 2

            ModFaderSelector(
                depth = duoModSourceLevel,
                onDepthChange = { voiceActions.setDuoModSourceLevel(duoIndex, it) },
                activeSource = duoModSource,
                onSourceChange = { voiceActions.setDuoModSource(duoIndex, it) },
                color = color,
                controlId = VoiceSymbol.duoModSource(duoIndex).controlId.key
            )
        }

        // Main Content: Two Voice Columns side-by-side with optional center overlay
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
            // VOICE A COLUMN
            Column(
                modifier = Modifier.fillMaxHeight().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                val duoIndex = voiceA / 2
                // Knobs
                VoiceColumnMod(
                    modifier = Modifier.weight(1f),
                    voiceIndex = voiceA,
                    duoIndex = duoIndex,
                    tune = voiceStateA.tune,
                    duoMorph = duoMorph,
                    envSpeed = envSpeedA,
                    voiceActions = voiceActions,
                )

                // Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TriggerButton(
                        voiceA + 1,
                        voiceStateA.isHolding,
                        {
                            voiceActions.setHold(
                                voiceA,
                                it
                            )
                        },
                        "$VOICE_URI:hold_$voiceA"
                    )
                    PulseButton(
                        modifier = Modifier.offset(y = (-2).dp),
                        onPulseStart = {
                            voiceActions.pulseStart(
                                voiceA
                            )
                        },
                        onPulseEnd = {
                            voiceActions.pulseEnd(voiceA)
                            voiceActions.wobblePulseEnd(voiceA)
                        },
                        size = 28.dp,
                        label = "",
                        isActive = voiceStateA.pulse,
                        isLearnMode = midiState.isLearnModeActive,
                        isLearning =
                            isVoiceBeingLearned(
                                voiceA
                            ),
                        onLearnSelect = {
                            midiActions
                                .selectVoiceForLearning(voiceA)
                        },
                        onPulseStartWithPosition = { x, y ->
                            voiceActions.wobblePulseStart(voiceA, x, y)
                        },
                        onWobbleMove = { x, y ->
                            voiceActions.wobbleMove(voiceA, x, y)
                        }
                    )
                }
            }

            // VOICE B COLUMN
            Column(
                modifier = Modifier.fillMaxHeight().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                val duoIndex = voiceA / 2

                // Knobs
                VoiceColumnSharp(
                    modifier = Modifier.weight(1f),
                    voiceIndex = voiceB,
                    duoIndex = duoIndex,
                    tune = voiceStateB.tune,
                    sharpness = sharpness,
                    envSpeed = envSpeedB,
                    voiceActions = voiceActions
                )

                // Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TriggerButton(
                        voiceB + 1,
                        voiceStateB.isHolding,
                        {
                            voiceActions.setHold(
                            voiceB,
                                it
                            )
                        },
                        "$VOICE_URI:hold_$voiceB"
                    )
                    PulseButton(
                        modifier = Modifier.offset(y = (-2).dp),
                        onPulseStart = {
                            voiceActions.pulseStart(
                                voiceB
                            )
                        },
                        onPulseEnd = {
                            voiceActions.pulseEnd(voiceB)
                            voiceActions.wobblePulseEnd(voiceB)
                        },
                        size = 28.dp,
                        label = "",
                        isActive = voiceStateB.pulse,
                        isLearnMode = midiState.isLearnModeActive,
                        isLearning =
                            isVoiceBeingLearned(
                                voiceB
                            ),
                        onLearnSelect = {
                            midiActions
                                .selectVoiceForLearning(voiceB)
                        },
                        onPulseStartWithPosition = { x, y ->
                            voiceActions.wobblePulseStart(voiceB, x, y)
                        },
                        onWobbleMove = { x, y ->
                            voiceActions.wobbleMove(voiceB, x, y)
                        }
                    )
                }
            }
            } // end Row

            // Engine picker + harmonics knob overlaid between the 4 knobs
            Column(
                modifier = Modifier.align(
                    BiasAlignment(horizontalBias = 0f, verticalBias = -0.35f)
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EnginePickerButton(
                    currentEngine = duoEngine,
                    onEngineChange = { voiceActions.setDuoEngine(voiceA / 2, it) },
                    color = color,
                    label = engineLabel(duoEngine),
                    showExternalSelection = aiVoiceEngineHighlight,
                )
                RotaryKnob(
                    value = duoHarmonics,
                    onValueChange = { voiceActions.setDuoHarmonics(voiceA / 2, it) },
                    label = "\u2261",
                    size = 28.dp,
                    progressColor = color
                )
            }
        } // end Box
    } // end outer Column
}

@Preview
@Composable
fun DuoVoiceBoxPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val midiFeature = MidiViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val midiState by midiFeature.stateFlow.collectAsState()
    val voiceActions = voiceFeature.actions.toVoiceActions()
    val midiActions = midiFeature.actions.toMidiActions()

    LiquidPreviewContainerWithGradient {
        DuoVoiceBox(
            voiceA = 0,
            voiceB = 1,
            color = OrpheusColors.evoGold,
            voiceStateA = voiceState.voiceStates[0],
            voiceStateB = voiceState.voiceStates[1],
            sharpness = voiceState.duoSharpness[0],
            envSpeedA = voiceState.voiceEnvelopeSpeeds[0],
            envSpeedB = voiceState.voiceEnvelopeSpeeds[1],
            duoModSource = voiceState.duoModSources[0],
            duoEngine = voiceState.duoEngines[0],
            duoHarmonics = voiceState.duoHarmonics[0],
            duoMorph = voiceState.duoMorphs[0],
            duoModSourceLevel = voiceState.duoModSourceLevels[0],
            midiState = midiState,
            voiceActions = voiceActions,
            midiActions = midiActions,
            isVoiceBeingLearned = { false },
            modifier = Modifier.padding(16.dp)
        )
    }
}
