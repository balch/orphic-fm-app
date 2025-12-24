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
import io.github.fletchmckee.liquid.liquid
import org.balch.songe.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.songe.features.voice.VoiceViewModel
import org.balch.songe.ui.panels.LocalLiquidEffects
import org.balch.songe.ui.panels.LocalLiquidState
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
    val liquidState = LocalLiquidState.current

    // More varied duo colors for visual interest
    val duoColors =
        if (voiceStartIndex == 0) {
            listOf(SongeColors.neonMagenta, SongeColors.electricBlue) // 1-2, 3-4
        } else {
            listOf(SongeColors.warmGlow, SongeColors.synthGreen) // 5-6, 7-8
        }

    val shape = RoundedCornerShape(10.dp)
    
    // Base modifier with liquid effect - using viz-specific effects
    val effects = LocalLiquidEffects.current
    val baseModifier = modifier.clip(shape)
    val liquidModifier = if (liquidState != null) {
        baseModifier.liquid(liquidState) {
            frost = effects.frostMedium.dp
            this.shape = shape
            tint = quadColor.copy(alpha = effects.tintAlpha)
            saturation = effects.bottom.saturation
            contrast = effects.bottom.contrast
            edge = effects.bottom.edge
            dispersion = effects.bottom.dispersion
            refraction = effects.bottom.refraction
            curve = effects.bottom.curve
        }
    } else {
        baseModifier.background(SongeColors.darkVoid.copy(alpha = 0.5f))
    }

    Column(
        modifier = liquidModifier
            .border(1.dp, quadColor.copy(alpha = 0.3f), shape)
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
