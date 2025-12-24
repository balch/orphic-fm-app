package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import org.balch.songe.ui.theme.LiquidEffects
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.CrossModSelector
import org.balch.songe.ui.widgets.RotaryKnob

@Composable
fun CenterControlPanel() {
    val voiceViewModel: VoiceViewModel = metroViewModel()
    val voiceState by voiceViewModel.uiState.collectAsState()
    val liquidState = LocalLiquidState.current

    val shape = RoundedCornerShape(8.dp)
    
    // Base modifier with liquid effect - using theme constants
    val baseModifier = Modifier.clip(shape)
    val liquidModifier = if (liquidState != null) {
        baseModifier.liquid(liquidState) {
            frost = LiquidEffects.FROST_LARGE.dp
            this.shape = shape
            refraction = LiquidEffects.REFRACTION
            curve = LiquidEffects.CURVE
            tint = SongeColors.electricBlue.copy(alpha = LiquidEffects.TINT_ALPHA)
            saturation = LiquidEffects.SATURATION
            contrast = LiquidEffects.CONTRAST
        }
    } else {
        baseModifier.background(SongeColors.darkVoid.copy(alpha = 0.4f))
    }

    Column(
        modifier = liquidModifier
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "SONGE-8",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f)
        )
        CrossModSelector(
            isCrossQuad = voiceState.fmStructureCrossQuad,
            onToggle = {
                voiceViewModel
                    .onFmStructureChange(it)
            }
        )
        RotaryKnob(
            value = voiceState.totalFeedback,
            onValueChange = {
                voiceViewModel
                    .onTotalFeedbackChange(it)
            },
            label = "TOTAL FB",
            controlId = ControlIds.TOTAL_FEEDBACK,
            size = 32.dp,
            progressColor = SongeColors.neonCyan
        )
        RotaryKnob(
            value = voiceState.vibrato,
            onValueChange = {
                voiceViewModel.onVibratoChange(it)
            },
            label = "VIB",
            controlId = ControlIds.VIBRATO,
            size = 32.dp,
            progressColor = SongeColors.neonMagenta
        )
        RotaryKnob(
            value = voiceState.voiceCoupling,
            onValueChange = {
                voiceViewModel
                    .onVoiceCouplingChange(it)
            },
            label = "COUPLE",
            controlId = ControlIds.VOICE_COUPLING,
            size = 32.dp,
            progressColor = SongeColors.warmGlow
        )
    }
}
