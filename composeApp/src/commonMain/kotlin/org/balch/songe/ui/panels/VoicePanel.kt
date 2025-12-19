package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import org.balch.songe.ui.components.HoldButton
import org.balch.songe.ui.components.PulseButton
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun VoicePanel(
    voiceIndex: Int,
    tune: Float,
    onTuneChange: (Float) -> Unit,
    fmDepth: Float,
    onFmDepthChange: (Float) -> Unit,
    isFastEnvelope: Boolean,
    onEnvelopeModeChange: (Boolean) -> Unit,
    pulseStrength: Float, // Visual feedback only
    onPulseStart: () -> Unit,
    onPulseEnd: () -> Unit,
    isHolding: Boolean,
    onHoldChange: (Boolean) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState, style = HazeMaterials.thin())
                } else {
                    Modifier
                }
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SongeColors.softPurple.copy(alpha = 0.2f),
                        SongeColors.deepPurple.copy(alpha = 0.5f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.4f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.5f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "V${voiceIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = SongeColors.electricBlue
        )
        
        // Knobs Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = tune,
                onValueChange = onTuneChange,
                label = "TUNE",
                size = 36.dp,
                indicatorColor = SongeColors.neonMagenta
            )
            RotaryKnob(
                value = fmDepth,
                onValueChange = onFmDepthChange,
                label = "MOD",
                size = 36.dp,
                indicatorColor = SongeColors.electricBlue
            )
        }
        
        // Envelope Mode Toggle
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isFastEnvelope) SongeColors.neonCyan else Color(0xFF2A2A3A))
                    .border(1.dp, if (isFastEnvelope) SongeColors.neonCyan else Color(0xFF4A4A5A), RoundedCornerShape(4.dp))
                    .clickable { onEnvelopeModeChange(true) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "FAST",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFastEnvelope) Color.White else Color(0xFF888888)
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (!isFastEnvelope) SongeColors.softPurple else Color(0xFF2A2A3A))
                    .border(1.dp, if (!isFastEnvelope) SongeColors.softPurple else Color(0xFF4A4A5A), RoundedCornerShape(4.dp))
                    .clickable { onEnvelopeModeChange(false) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SLOW",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (!isFastEnvelope) Color.White else Color(0xFF888888)
                )
            }
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulseButton(
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                size = 28.dp
            )
            
            HoldButton(
                checked = isHolding,
                onCheckedChange = onHoldChange
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
@Preview
fun VoicePanelPreview() {
    MaterialTheme {
        VoicePanel(
            voiceIndex = 0,
            tune = 0.5f,
            onTuneChange = {},
            fmDepth = 0.0f,
            onFmDepthChange = {},
            isFastEnvelope = false,
            onEnvelopeModeChange = {},
            pulseStrength = 0f,
            onPulseStart = {},
            onPulseEnd = {},
            isHolding = false,
            onHoldChange = {},
            hazeState = null
        )
    }
}
