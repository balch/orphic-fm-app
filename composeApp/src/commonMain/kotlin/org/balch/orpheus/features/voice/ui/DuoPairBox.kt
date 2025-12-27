package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.widgets.HorizontalSwitch3Way
import org.balch.orpheus.ui.widgets.PulseButton

import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.voice.VoiceUiState

@Composable
fun DuoPairBox(
    modifier: Modifier = Modifier,
    voiceA: Int,
    voiceB: Int,
    color: Color,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    midiViewModel: MidiViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val midiState by midiViewModel.uiState.collectAsState()

    val voiceActions = object : VoiceActions {
        override fun onDuoModSourceChange(pairIndex: Int, source: org.balch.orpheus.core.audio.ModSource) = voiceViewModel.onDuoModSourceChange(pairIndex, source)
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
        override fun onDialogActiveChange(active: Boolean) { /* Not used here */ }
        override fun onWobblePulseStart(index: Int, x: Float, y: Float) = voiceViewModel.onWobblePulseStart(index, x, y)
        override fun onWobbleMove(index: Int, x: Float, y: Float) = voiceViewModel.onWobbleMove(index, x, y)
        override fun onWobblePulseEnd(index: Int) = voiceViewModel.onWobblePulseEnd(index)
    }

    val midiActions = object : MidiActions {
        override fun selectVoiceForLearning(voiceIndex: Int) = midiViewModel.selectVoiceForLearning(voiceIndex)
    }

    DuoPairBoxLayout(
        modifier = modifier,
        voiceA = voiceA,
        voiceB = voiceB,
        color = color,
        voiceState = voiceState,
        midiState = midiState,
        voiceActions = voiceActions,
        midiActions = midiActions,
        isVoiceBeingLearned = midiViewModel::isVoiceBeingLearned
    )
}

@Composable
fun DuoPairBoxLayout(
    modifier: Modifier = Modifier,
    voiceA: Int,
    voiceB: Int,
    color: Color,
    voiceState: VoiceUiState,
    midiState: MidiUiState,
    voiceActions: VoiceActions,
    midiActions: MidiActions,
    isVoiceBeingLearned: (Int) -> Boolean
) {
    Column(
        modifier =
            modifier.widthIn(min = 100.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                // Removed hardcoded background - let liquid effect from parent show through
                .border(2.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))  // Brighter border
                .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Full-width header bar with Duo label and LFO toggle
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

            // 3-Way Mod Source Switch (Horizontal)
            // Calculate pair index (0,1,2,3)
            val pairIndex = voiceA / 2

            HorizontalSwitch3Way(
                state = voiceState.duoModSources[pairIndex],
                onStateChange = {
                    voiceActions.onDuoModSourceChange(pairIndex, it)
                },
                color = color,
                controlId = ControlIds.duoModSource(pairIndex)
            )
        }

        // Main Content: Two Voice Columns side-by-side
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 0.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // VOICE A COLUMN
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                val pairIndex = voiceA / 2
                // Knobs
                VoiceColumnMod(
                    modifier = Modifier.weight(1f),
                    num = voiceA + 1,
                    voiceIndex = voiceA,
                    pairIndex = pairIndex,
                    tune = voiceState.voiceStates[voiceA].tune,
                    onTuneChange = {
                        voiceActions.onVoiceTuneChange(
                            voiceA,
                            it
                        )
                    },
                    modDepth = voiceState.voiceModDepths[voiceA],
                    onModDepthChange = {
                        voiceActions.onDuoModDepthChange(
                            pairIndex,
                            it
                        )
                    }, // Apply to both voices
                    envSpeed = voiceState.voiceEnvelopeSpeeds[voiceA],
                    onEnvSpeedChange = {
                        voiceActions.onVoiceEnvelopeSpeedChange(
                            voiceA,
                            it
                        )
                    }
                )

                // Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TriggerButton(
                        voiceA + 1,
                        voiceState.voiceStates[voiceA].isHolding,
                        {
                            voiceActions.onHoldChange(
                                voiceA,
                                it
                            )
                        },
                        ControlIds.voiceHold(voiceA)
                    )
                    PulseButton(
                        modifier = Modifier.offset(y = (-2).dp),
                        onPulseStart = {
                            voiceActions.onPulseStart(
                                voiceA
                            )
                        },
                        onPulseEnd = {
                            voiceActions.onPulseEnd(voiceA)
                            voiceActions.onWobblePulseEnd(voiceA)
                        },
                        size = 28.dp,
                        label = "",
                        isActive = voiceState.voiceStates[voiceA].pulse,
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
                            voiceActions.onWobblePulseStart(voiceA, x, y)
                        },
                        onWobbleMove = { x, y ->
                            voiceActions.onWobbleMove(voiceA, x, y)
                        }
                    )
                }
            }

            // VOICE B COLUMN
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Calculate pair index (0-3) from voice A (0,2,4,6)
                val pairIndex = voiceA / 2

                // Knobs
                VoiceColumnSharp(
                    modifier = Modifier.weight(1f),
                    num = voiceB + 1,
                    voiceIndex = voiceB,
                    pairIndex = pairIndex,
                    tune = voiceState.voiceStates[voiceB].tune,
                    onTuneChange = {
                        voiceActions.onVoiceTuneChange(
                            voiceB,
                            it
                        )
                    },
                    sharpness = voiceState.pairSharpness[pairIndex],
                    onSharpnessChange = {
                        voiceActions.onPairSharpnessChange(
                            pairIndex,
                            it
                        )
                    },
                    envSpeed = voiceState.voiceEnvelopeSpeeds[voiceB],
                    onEnvSpeedChange = {
                        voiceActions.onVoiceEnvelopeSpeedChange(
                            voiceB,
                            it
                        )
                    }
                )

                // Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TriggerButton(
                        voiceB + 1,
                        voiceState.voiceStates[voiceB].isHolding,
                        {
                            voiceActions.onHoldChange(
                            voiceB,
                                it
                            )
                        },
                        ControlIds.voiceHold(voiceB)
                    )
                    PulseButton(
                        modifier = Modifier.offset(y = (-2).dp),
                        onPulseStart = {
                            voiceActions.onPulseStart(
                                voiceB
                            )
                        },
                        onPulseEnd = {
                            voiceActions.onPulseEnd(voiceB)
                            voiceActions.onWobblePulseEnd(voiceB)
                        },
                        size = 28.dp,
                        label = "",
                        isActive = voiceState.voiceStates[voiceB].pulse,
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
                            voiceActions.onWobblePulseStart(voiceB, x, y)
                        },
                        onWobbleMove = { x, y ->
                            voiceActions.onWobbleMove(voiceB, x, y)
                        }
                    )
                }
            }
        }
    }
}
