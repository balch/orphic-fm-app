package org.balch.orpheus.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

@Composable
fun ModSourceSelector(
    modifier: Modifier = Modifier,
    activeSource: ModSource,
    onSourceChange: (ModSource) -> Unit,
    color: Color = OrpheusColors.warmGlow,
    controlId: String? = null,
) {
    val learnState = LocalLearnModeState.current
    val finalModifier = if (controlId != null) {
        modifier.learnable(controlId, learnState)
    } else {
        modifier
    }

    Box(
        modifier = finalModifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(enabled = !learnState.isActive) {
                // Cycle: OFF -> LFO -> FM -> FLUX -> OFF
                val nextSource = when (activeSource) {
                    ModSource.OFF -> ModSource.LFO
                    ModSource.LFO -> ModSource.VOICE_FM
                    ModSource.VOICE_FM -> ModSource.FLUX
                    ModSource.FLUX -> ModSource.OFF
                }
                onSourceChange(nextSource)
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .widthIn(min = 60.dp), // Ensure stable width
        contentAlignment = Alignment.Center
    ) {
         AnimatedContent(
            targetState = activeSource,
            transitionSpec = {
                // Slide up/down effect for cycling text
                (slideInVertically { height -> height } + fadeIn()).togetherWith(
                    slideOutVertically { height -> -height } + fadeOut())
            },
            label = "ModSourceText"
        ) { source ->
             val label = when (source) {
                 ModSource.OFF -> "OFF"
                 ModSource.LFO -> "LFO"
                 ModSource.VOICE_FM -> "FM"
                 ModSource.FLUX -> "FLUX"
             }
             
             Text(
                 text = label,
                 fontSize = 9.sp,
                 fontWeight = FontWeight.Bold,
                 color = if (source == ModSource.OFF) color.copy(alpha = 0.6f) else color,
                 textAlign = TextAlign.Center
             )
        }
    }
}

@Preview
@Composable
fun ModSourceSelectorPreview() {
    OrpheusTheme {
        Row {
             ModSourceSelector(activeSource = ModSource.OFF, onSourceChange = {}, color = OrpheusColors.neonMagenta)
             ModSourceSelector(activeSource = ModSource.FLUX, onSourceChange = {}, color = OrpheusColors.neonCyan)
        }
    }
}
