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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors

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
    duoIndex: Int,
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    borderColor: Color = OrpheusColors.neonMagenta,
    liquidState: LiquidState? = null,
    effects: VisualizationLiquidEffects = VisualizationLiquidEffects(),
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val startIndex = duoIndex * 2

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                repeat(2) { i ->
                    val voiceIndex = startIndex + i
                    CompactVoicePanel(
                        voiceIndex = voiceIndex,
                        voiceFeature = voiceFeature,
                        color = borderColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun CompactDuoLiquidPanelPreview() {
    Row(
        modifier = Modifier.background(Color.Black).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CompactDuoLiquidPanel(
            duoIndex = 0,
            voiceFeature = VoiceViewModel.previewFeature(),
            borderColor = OrpheusColors.neonMagenta,
            liquidState = null,
            effects = VisualizationLiquidEffects.Default,
            modifier = Modifier.width(140.dp).height(200.dp)
        )
    }
}
