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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.audio.patch.modulatedDests
import org.balch.orpheus.core.audio.patch.modulatedVoiceDests
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.ModDepthFader
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.DefaultValueFormatter
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.engine.EnginePicker
import org.balch.orpheus.ui.widgets.engine.rememberEnginePickerController
import org.balch.orpheus.ui.widgets.patch.ModDotBox
import org.balch.orpheus.ui.widgets.patch.PatchBayButton
import kotlin.math.min

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
    duoPatch: DuoPatch,
    duoEngine: Int,
    duoHarmonics: Float,
    duoMorph: Float,
    duoModSourceLevel: Float,
    midiState: MidiUiState,
    voiceActions: VoiceActions,
    midiActions: MidiActions,
    isVoiceBeingLearned: (Int) -> Boolean,
    aiVoiceEngineHighlight: Boolean = false,
    hasNativeEngine: Boolean = false,
    glowEnergy: Float = 0f, // TODO: wire to real audio energy from SynthEngine voice levels
) {
    val engineId = OrpheusEngineId.fromId(duoEngine) ?: OrpheusEngineId.OSC
    val morphLabel = duoKnobLabel(engineId, EngineParam.MORPH, duoHarmonics)
    val sharpLabel = duoKnobLabel(engineId, EngineParam.TIMBRE, duoHarmonics)
    val modulated = remember(duoPatch, engineId) { duoPatch.modulatedDests(engineId) }
    val modulatedA = remember(duoPatch, engineId) {
        modulated.filterTo(mutableSetOf()) { !it.perVoice } + duoPatch.modulatedVoiceDests(engineId, VoiceSlot.A)
    }
    val modulatedB = remember(duoPatch, engineId) {
        modulated.filterTo(mutableSetOf()) { !it.perVoice } + duoPatch.modulatedVoiceDests(engineId, VoiceSlot.B)
    }

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

            ModDepthFader(
                depth = duoModSourceLevel,
                onDepthChange = { voiceActions.setDuoModSourceLevel(duoIndex, it) },
                color = color,
                controlId = VoiceSymbol.duoModSourceLevel(duoIndex).controlId.key
            )

            PatchBayButton(
                duoIndex = duoIndex,
                patch = duoPatch,
                engine = engineId,
                onPatchChange = { voiceActions.setDuoPatch(duoIndex, it) },
                color = color,
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
                    morphLabel = morphLabel,
                    modulated = modulatedA,
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
                    sharpLabel = sharpLabel,
                    modulated = modulatedB,
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

            // Engine pill between the top dials; harmonics knob between the side knob groups.
            DuoEngineControls(
                duoIndex = voiceA / 2,
                engineId = engineId,
                harmonics = duoHarmonics,
                color = color,
                hasNativeEngine = hasNativeEngine,
                glowEnergy = glowEnergy,
                showExternalSelection = aiVoiceEngineHighlight,
                harmonicsModulated = PatchDest.HARMONICS in modulated,
                voiceActions = voiceActions,
                pillModifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
                knobModifier = Modifier.align(BiasAlignment(horizontalBias = 0f, verticalBias = -0.4f)),
            )
        } // end Box
    } // end outer Column
}

/**
 * The duo's engine pill and harmonics knob. A patch pick sets the DX engine and its harmonics;
 * on DX the knob locks and a click opens this picker on DX Patches.
 */
@Composable
internal fun DuoEngineControls(
    duoIndex: Int,
    engineId: OrpheusEngineId,
    harmonics: Float,
    color: Color,
    hasNativeEngine: Boolean,
    glowEnergy: Float,
    showExternalSelection: Boolean,
    voiceActions: VoiceActions,
    harmonicsModulated: Boolean = false,
    pillModifier: Modifier = Modifier,
    knobModifier: Modifier = Modifier,
) {
    val controller = rememberEnginePickerController()
    val choices = remember(hasNativeEngine) { EngineCatalog.choices(EngineSlotKind.DUO, hasNativeEngine) }
    val harmonicsLabel = duoKnobLabel(engineId, EngineParam.HARMONICS, harmonics)
    // Keyed on the patch, not the float, so a knob drag doesn't rebuild the choice every frame.
    val patchIndex = EngineCatalog.patchAt(engineId, harmonics)?.index
    val current = remember(engineId, patchIndex) { EngineCatalog.choiceFor(engineId, harmonics) }
    EnginePicker(
        current = current,
        choices = choices,
        onPick = { choice ->
            voiceActions.setDuoEngine(duoIndex, choice.engine.id)
            if (choice is EngineChoice.Patch) voiceActions.setDuoHarmonics(duoIndex, choice.patch.harmonics)
        },
        color = color,
        compact = true,
        pillHeight = 22.dp,
        glowEnergy = glowEnergy,
        showExternalSelection = showExternalSelection,
        controller = controller,
        // The code only: the locked knob below names the patch.
        pillLabel = (current as? EngineChoice.Patch)?.patch?.engine?.name,
        modifier = pillModifier.capWidth(),
    )
    ModDotBox(PatchDest.HARMONICS, harmonicsModulated, color, knobModifier.capWidth()) {
        RotaryKnob(
            value = harmonics,
            onValueChange = { voiceActions.setDuoHarmonics(duoIndex, it) },
            label = harmonicsLabel.short,
            caption = harmonicsLabel.glyph,
            longLabel = harmonicsLabel.long,
            locked = harmonicsLabel.locked,
            onLockedClick = { controller.open(EngineCategory.DX_PATCHES) },
            valueFormatter = if (harmonicsLabel.locked) {
                { v -> EngineCatalog.patchAt(engineId, v)?.let { "#${it.index + 1}" } ?: "" }
            } else {
                DefaultValueFormatter
            },
            size = 28.dp,
            progressColor = color,
            labelColor = if (harmonicsLabel.active) color else color.copy(alpha = 0.4f),
        )
    }
}

/**
 * The centre overlay sits in the gap between the voice columns' knobs, about 30% of the duo's
 * width, so the pill and knob label truncate there rather than run over the side knobs.
 */
private fun Modifier.capWidth(fraction: Float = 0.3f, max: Dp = 84.dp): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) {
        min((constraints.maxWidth * fraction).toInt(), max.roundToPx())
    } else {
        max.roundToPx()
    }
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap.coerceAtLeast(0)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Preview
@Composable
fun DuoVoiceBoxPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val midiFeature = MidiViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsStateWithLifecycle()
    val midiState by midiFeature.stateFlow.collectAsStateWithLifecycle()
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
            duoPatch = DuoPatch()
                .connect(PatchSource.LFO_A, PatchDest.MORPH, VoiceSlot.BOTH, 0.5f)
                .connect(PatchSource.WAVES_1, PatchDest.PITCH, VoiceSlot.BOTH, 0.3f),
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

@Preview(name = "Duo on a DX2 patch: harmonics locked")
@Composable
fun DuoVoiceBoxDxPreview() {
    val voiceFeature = VoiceViewModel.previewFeature()
    val midiFeature = MidiViewModel.previewFeature()
    val voiceState by voiceFeature.stateFlow.collectAsStateWithLifecycle()
    val midiState by midiFeature.stateFlow.collectAsStateWithLifecycle()
    val ePiano = EngineCatalog.patches.first { it.engine == OrpheusEngineId.DX2 && it.index == 0 }

    LiquidPreviewContainerWithGradient {
        DuoVoiceBox(
            voiceA = 2,
            voiceB = 3,
            color = OrpheusColors.electricBlue,
            voiceStateA = voiceState.voiceStates[2],
            voiceStateB = voiceState.voiceStates[3],
            sharpness = voiceState.duoSharpness[1],
            envSpeedA = voiceState.voiceEnvelopeSpeeds[2],
            envSpeedB = voiceState.voiceEnvelopeSpeeds[3],
            duoPatch = DuoPatch().connect(PatchSource.FLUX_X1, PatchDest.ENV, VoiceSlot.BOTH, 0.4f),
            duoEngine = OrpheusEngineId.DX2.id,
            duoHarmonics = ePiano.harmonics,
            duoMorph = voiceState.duoMorphs[1],
            duoModSourceLevel = voiceState.duoModSourceLevels[1],
            midiState = midiState,
            voiceActions = voiceFeature.actions.toVoiceActions(),
            midiActions = midiFeature.actions.toMidiActions(),
            isVoiceBeingLearned = { false },
            hasNativeEngine = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}
