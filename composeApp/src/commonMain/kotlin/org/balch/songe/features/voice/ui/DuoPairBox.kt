package org.balch.songe.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.balch.songe.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.songe.features.midi.MidiViewModel
import org.balch.songe.features.voice.VoiceViewModel
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.HorizontalSwitch3Way
import org.balch.songe.ui.widgets.PulseButton

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

    Column(
        modifier =
            modifier.widthIn(min = 100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.12f))  // Tinted with duo color for visibility
                .border(2.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))  // Brighter border
                .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Full-width header bar with Duo label and LFO toggle
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.25f))  // More visible header
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
                    voiceViewModel.onDuoModSourceChange(pairIndex, it)
                },
                color = color,
                controlId = ControlIds.duoModSource(pairIndex)
            )
        }

        // Main Content: Two Voice Columns side-by-side
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // VOICE A COLUMN
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val pairIndex = voiceA / 2
                // Knobs
                VoiceColumnMod(
                    num = voiceA + 1,
                    voiceIndex = voiceA,
                    pairIndex = pairIndex,
                    tune = voiceState.voiceStates[voiceA].tune,
                    onTuneChange = {
                        voiceViewModel.onVoiceTuneChange(
                            voiceA,
                            it
                        )
                    },
                    modDepth = voiceState.voiceModDepths[voiceA],
                    onModDepthChange = {
                        voiceViewModel.onDuoModDepthChange(
                            pairIndex,
                            it
                        )
                    }, // Apply to both voices
                    envSpeed = voiceState.voiceEnvelopeSpeeds[voiceA],
                    onEnvSpeedChange = {
                        voiceViewModel.onVoiceEnvelopeSpeedChange(
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
                            voiceViewModel.onHoldChange(
                                voiceA,
                                it
                            )
                        },
                        ControlIds.voiceHold(voiceA)
                    )
                    PulseButton(
                        onPulseStart = {
                            voiceViewModel.onPulseStart(
                                voiceA
                            )
                        },
                        onPulseEnd = {
                            voiceViewModel.onPulseEnd(voiceA)
                        },
                        size = 28.dp,
                        label = "",
                        isActive = voiceState.voiceStates[voiceA].pulse,
                        isLearnMode = midiState.isLearnModeActive,
                        isLearning =
                            midiViewModel.isVoiceBeingLearned(
                                voiceA
                            ),
                        onLearnSelect = {
                            midiViewModel
                                .selectVoiceForLearning(voiceA)
                        }
                    )
                }
            }

            // VOICE B COLUMN
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Calculate pair index (0-3) from voice A (0,2,4,6)
                val pairIndex = voiceA / 2

                // Knobs
                VoiceColumnSharp(
                    num = voiceB + 1,
                    voiceIndex = voiceB,
                    pairIndex = pairIndex,
                    tune = voiceState.voiceStates[voiceB].tune,
                    onTuneChange = {
                        voiceViewModel.onVoiceTuneChange(
                            voiceB,
                            it
                        )
                    },
                    sharpness = voiceState.pairSharpness[pairIndex],
                    onSharpnessChange = {
                        voiceViewModel.onPairSharpnessChange(
                            pairIndex,
                            it
                        )
                    },
                    envSpeed = voiceState.voiceEnvelopeSpeeds[voiceB],
                    onEnvSpeedChange = {
                        voiceViewModel.onVoiceEnvelopeSpeedChange(
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
                            voiceViewModel.onHoldChange(
                                voiceB,
                                it
                            )
                        },
                        ControlIds.voiceHold(voiceB)
                    )
                    PulseButton(
                        onPulseStart = {
                            voiceViewModel.onPulseStart(
                                voiceB
                            )
                        },
                        onPulseEnd = {
                            voiceViewModel.onPulseEnd(voiceB)
                        },
                        size = 28.dp,
                        label = "",
                        isActive = voiceState.voiceStates[voiceB].pulse,
                        isLearnMode = midiState.isLearnModeActive,
                        isLearning =
                            midiViewModel.isVoiceBeingLearned(
                                voiceB
                            ),
                        onLearnSelect = {
                            midiViewModel
                                .selectVoiceForLearning(voiceB)
                        }
                    )
                }
            }
        }
    }
}
