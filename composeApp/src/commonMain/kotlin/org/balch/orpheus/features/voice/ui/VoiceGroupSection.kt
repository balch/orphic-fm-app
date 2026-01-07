package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun VoiceGroupSection(
    modifier: Modifier = Modifier,
    quadLabel: String,
    quadColor: Color,
    voiceStartIndex: Int,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    midiViewModel: MidiViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val midiState by midiViewModel.uiState.collectAsState()
    val effects = LocalLiquidEffects.current

    val voiceActions = voiceViewModel.panelActions.toVoiceActions()

    val midiActions = midiViewModel.panelActions.toMidiActions()

    VoiceGroupSectionLayout(
        modifier = modifier,
        quadLabel = quadLabel,
        quadColor = quadColor,
        voiceStartIndex = voiceStartIndex,
        voiceState = voiceState,
        midiState = midiState,
        voiceActions = voiceActions,
        midiActions = midiActions,
        isVoiceBeingLearned = midiViewModel::isVoiceBeingLearned,
        effects = effects
    )
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
                    voiceActions.onQuadPitchChange(quadIndex, it)
                },
                label = "PITCH",
                controlId = ControlIds.quadPitch(quadIndex),
                size = 36.dp,
                progressColor = quadColor
            )
            RotaryKnob(
                value = voiceState.quadGroupHolds[quadIndex],
                onValueChange = {
                    voiceActions.onQuadHoldChange(quadIndex, it)
                },
                label = "HOLD",
                controlId = ControlIds.quadHold(quadIndex),
                size = 36.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }

        // Two Duo groups side by side
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DuoPairBoxLayout(
                voiceA = voiceStartIndex,
                voiceB = voiceStartIndex + 1,
                color = duoColors[0],
                modifier = Modifier.weight(1f).fillMaxHeight(),
                voiceState = voiceState,
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = isVoiceBeingLearned
            )
            DuoPairBoxLayout(
                voiceA = voiceStartIndex + 2,
                voiceB = voiceStartIndex + 3,
                color = duoColors[1],
                modifier = Modifier.weight(1f).fillMaxHeight(),
                voiceState = voiceState,
                midiState = midiState,
                voiceActions = voiceActions,
                midiActions = midiActions,
                isVoiceBeingLearned = isVoiceBeingLearned
            )
        }
    }
}

