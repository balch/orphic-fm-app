package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.EnginePickerPopup
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.PICKER_SIZE
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.computePickerSegment
import org.balch.orpheus.ui.widgets.engineLabel
import org.balch.orpheus.ui.widgets.pickerSegmentToOrdinal
import kotlin.math.sqrt

@Composable
fun DuoPairBox(
    modifier: Modifier = Modifier,
    voiceA: Int,
    voiceB: Int,
    color: Color,
    voiceStateA: VoiceState,
    voiceStateB: VoiceState,
    modDepthA: Float,
    sharpness: Float,
    envSpeedA: Float,
    envSpeedB: Float,
    duoModSource: ModSource,
    pairEngine: Int,
    pairHarmonics: Float,
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

            val pairIndex = voiceA / 2

            // Engine selector — press to open radial picker, release to select
            var showEnginePicker by remember { mutableStateOf(false) }
            var hoveredSegment by remember { mutableStateOf<Int?>(null) }
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.15f))
                        .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
                        .pointerInput(Unit) {
                            val pickerRadiusPx = PICKER_SIZE.toPx() / 2f
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                    .also { it.consume() }
                                showEnginePicker = true
                                hoveredSegment = null

                                var anyPressed = true
                                while (anyPressed) {
                                    val event = awaitPointerEvent()
                                    val pos = event.changes.firstOrNull()?.position
                                    if (pos != null) {
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val dx = pos.x - cx
                                        val dy = pos.y - cy
                                        val dist = sqrt(dx * dx + dy * dy)
                                        hoveredSegment =
                                            computePickerSegment(dx, dy, dist, pickerRadiusPx)
                                    }
                                    event.changes.forEach { it.consume() }
                                    anyPressed = event.changes.any { it.pressed }
                                }

                                val seg = hoveredSegment
                                if (seg != null) {
                                    voiceActions.setPairEngine(
                                        pairIndex,
                                        pickerSegmentToOrdinal(seg)
                                    )
                                }
                                showEnginePicker = false
                                hoveredSegment = null
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = engineLabel(pairEngine),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1
                    )
                }
                if (showEnginePicker) {
                    EnginePickerPopup(
                        currentEngine = pairEngine,
                        hoveredSegment = hoveredSegment,
                        color = color,
                    )
                }
            }

            // Harmonics knob (visible when Plaits engine active)
            if (pairEngine != 0) {
                RotaryKnob(
                    value = pairHarmonics,
                    onValueChange = { voiceActions.setPairHarmonics(pairIndex, it) },
                    label = "H",
                    size = 22.dp,
                    progressColor = color
                )
            }

            // Mod Source Selector (Cycles: OFF -> LFO -> FM -> FLUX)
            org.balch.orpheus.ui.widgets.ModSourceSelector(
                activeSource = duoModSource,
                onSourceChange = { newSource ->
                    voiceActions.setDuoModSource(pairIndex, newSource)
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
                    voiceIndex = voiceA,
                    pairIndex = pairIndex,
                    tune = voiceStateA.tune,
                    modDepth = modDepthA,
                    envSpeed = envSpeedA,
                    voiceActions = voiceActions,
                    isPlaitsActive = pairEngine != 0
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
                        ControlIds.voiceHold(voiceA)
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
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Calculate pair index (0-3) from voice A (0,2,4,6)
                val pairIndex = voiceA / 2

                // Knobs
                VoiceColumnSharp(
                    modifier = Modifier.weight(1f),
                    voiceIndex = voiceB,
                    pairIndex = pairIndex,
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
                        ControlIds.voiceHold(voiceB)
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
        }
}

}

@Preview
@Composable
fun DuoPairBoxPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val midiFeature = MidiViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val midiState by midiFeature.stateFlow.collectAsState()
    val voiceActions = voiceFeature.actions.toVoiceActions()
    val midiActions = midiFeature.actions.toMidiActions()

    LiquidPreviewContainerWithGradient {
        DuoPairBox(
            voiceA = 0,
            voiceB = 1,
            color = OrpheusColors.evoGold,
            voiceStateA = voiceState.voiceStates[0],
            voiceStateB = voiceState.voiceStates[1],
            modDepthA = voiceState.voiceModDepths[0],
            sharpness = voiceState.pairSharpness[0],
            envSpeedA = voiceState.voiceEnvelopeSpeeds[0],
            envSpeedB = voiceState.voiceEnvelopeSpeeds[1],
            duoModSource = voiceState.duoModSources[0],
            pairEngine = voiceState.pairEngines[0],
            pairHarmonics = voiceState.pairHarmonics[0],
            midiState = midiState,
            voiceActions = voiceActions,
            midiActions = midiActions,
            isVoiceBeingLearned = { false },
            modifier = Modifier.padding(16.dp)
        )
    }
}
