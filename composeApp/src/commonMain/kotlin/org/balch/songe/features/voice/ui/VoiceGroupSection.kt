package org.balch.songe.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.balch.songe.features.voice.VoiceViewModel
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.RotaryKnob

@Composable
fun VoiceGroupSection(
    modifier: Modifier = Modifier,
    quadLabel: String,
    quadColor: Color,
    voiceStartIndex: Int,
    voiceViewModel: VoiceViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()

    val duoColors =
        if (voiceStartIndex == 0) {
            listOf(SongeColors.neonMagenta, SongeColors.warmGlow) // 1-2, 3-4
        } else {
            listOf(SongeColors.neonCyan, SongeColors.synthGreen) // 5-6, 7-8
        }

    Column(
        modifier =
            modifier.clip(RoundedCornerShape(10.dp))
                .background(SongeColors.darkVoid.copy(alpha = 0.5f))
                .border(
                    1.dp,
                    quadColor.copy(alpha = 0.3f),
                    RoundedCornerShape(10.dp)
                )
                .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                    voiceViewModel.onQuadPitchChange(quadIndex, it)
                },
                label = "PITCH",
                controlId = ControlIds.quadPitch(quadIndex),
                size = 28.dp,
                progressColor = quadColor
            )
            RotaryKnob(
                value = voiceState.quadGroupHolds[quadIndex],
                onValueChange = {
                    voiceViewModel.onQuadHoldChange(quadIndex, it)
                },
                label = "HOLD",
                controlId = ControlIds.quadHold(quadIndex),
                size = 28.dp,
                progressColor = SongeColors.warmGlow
            )
        }

        // Two Duo groups side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DuoPairBox(
                voiceA = voiceStartIndex,
                voiceB = voiceStartIndex + 1,
                color = duoColors[0],
                modifier = Modifier.weight(1f)
            )
            DuoPairBox(
                voiceA = voiceStartIndex + 2,
                voiceB = voiceStartIndex + 3,
                color = duoColors[1],
                modifier = Modifier.weight(1f)
            )
        }
    }
}
