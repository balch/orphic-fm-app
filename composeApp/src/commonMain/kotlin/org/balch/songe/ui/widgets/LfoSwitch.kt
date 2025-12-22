package org.balch.songe.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview

enum class LfoWaveform {
    SINE, TRIANGLE, SQUARE
}

@Composable
@Preview
fun LfoSwitchPreview() {
    MaterialTheme {
        LfoSwitch(
            enabled = true,
            onEnabledChange = {},
            waveform = LfoWaveform.TRIANGLE,
            onWaveformChange = {}
        )
    }
}

@Composable
fun LfoSwitch(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    waveform: LfoWaveform,
    onWaveformChange: (LfoWaveform) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = SongeColors.electricBlue
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 3D Switch for ON/OFF (Reused logic from HoldButton)
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            SongeColors.darkVoid
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(14.dp)
                )
                .clickable { onEnabledChange(!enabled) }
        ) {
            Box(
                modifier = Modifier
                    .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(2.dp)
                    .size(24.dp)
                    .shadow(4.dp, CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (enabled) {
                                listOf(Color.White, activeColor)
                            } else {
                                listOf(Color.Gray, Color.DarkGray)
                            }
                        ),
                        shape = CircleShape
                    )
                    .border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Waveform selector - 3D Buttons Style
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(2.dp)
        ) {
            LfoWaveform.entries.forEach { wf ->
                val selected = wf == waveform
                
                // Individual Waveform Button
                Box(
                    modifier = Modifier
                        .clickable { if (enabled) onWaveformChange(wf) }
                        .padding(2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                             if (selected && enabled) {
                                 Modifier.background(
                                     brush = Brush.verticalGradient(
                                         colors = listOf(
                                             activeColor.copy(alpha = 0.2f),
                                             activeColor.copy(alpha = 0.5f)
                                         )
                                     )
                                 )
                             } else {
                                 Modifier.background(Color.Transparent)
                             }
                        )
                        .border(
                            width = if (selected && enabled) 1.dp else 0.dp,
                            color = if (selected && enabled) activeColor else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = wf.name.take(3), // SIN, TRI, SQU
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected && enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "LFO",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
