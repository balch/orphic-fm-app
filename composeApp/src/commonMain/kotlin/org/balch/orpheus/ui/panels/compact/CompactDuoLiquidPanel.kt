package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A glass panel containing a pair of voices (2 voices) with exact styling from reference.
 * 
 * Layout:
 * - Label at top (Magenta/Blue/Green/Green)
 * - Row for two voices
 *   - Each voice: Knob -> Slider -> Square Button
 */
@Composable
fun CompactDuoLiquidPanel(
    pairIndex: Int,
    voiceStates: List<VoiceState>,
    voiceEnvelopeSpeeds: List<Float>,
    onVoiceTuneChange: (Int, Float) -> Unit,
    onEnvelopeSpeedChange: (Int, Float) -> Unit,
    onPulseStart: (Int) -> Unit,
    onPulseEnd: (Int) -> Unit,
    onVoiceHoldChange: (Int, Boolean) -> Unit,
    borderColor: Color,
    accentColor: Color,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val startIndex = pairIndex * 2
    val pairLabel = "${startIndex + 1}-${startIndex + 2}"

    Box(
        modifier = modifier
            .clip(shape)
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.top,
                frostAmount = effects.frostSmall.dp,
                color = borderColor.copy(alpha = 0.4f),
                tintAlpha = effects.tintAlpha,
                shape = shape
            )
            .border(1.5.dp, borderColor.copy(alpha = 0.6f), shape)
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pair label at top
            Text(
                text = pairLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = borderColor
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(2) { i ->
                    val voiceIndex = startIndex + i
                    if (voiceIndex < voiceStates.size) {
                        val state = voiceStates[voiceIndex]
                        CompactVoicePanel(
                            voiceIndex = voiceIndex,
                            tune = state.tune,
                            envelopeSpeed = voiceEnvelopeSpeeds.getOrElse(voiceIndex) { 0.5f },
                            isActive = state.pulse,
                            isHolding = state.isHolding,
                            onTuneChange = { onVoiceTuneChange(voiceIndex, it) },
                            onEnvelopeSpeedChange = { onEnvelopeSpeedChange(voiceIndex, it) },
                            onPulseStart = { onPulseStart(voiceIndex) },
                            onPulseEnd = { onPulseEnd(voiceIndex) },
                            onHoldChange = { onVoiceHoldChange(voiceIndex, it) },
                            color = borderColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun CompactDuoLiquidPanelPreview() {
    val mockVoiceState = VoiceState(index = 0, tune = 0.5f)
    val mockVoiceStates = List(8) { i -> VoiceState(index = i, tune = 0.3f) }
    val mockEnvelopeSpeeds = List(8) { 0.7f }

    Row(
        modifier = Modifier.background(Color.Black).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CompactDuoLiquidPanel(
            pairIndex = 0,
            voiceStates = mockVoiceStates,
            voiceEnvelopeSpeeds = mockEnvelopeSpeeds,
            onVoiceTuneChange = { _, _ -> },
            onEnvelopeSpeedChange = { _, _ -> },
            onPulseStart = {},
            onPulseEnd = {},
            onVoiceHoldChange = { _, _ -> },
            borderColor = OrpheusColors.neonMagenta,
            accentColor = OrpheusColors.neonCyan,
            liquidState = null,
            effects = VisualizationLiquidEffects.Default,
            modifier = Modifier.width(140.dp).height(200.dp)
        )
    }
}
