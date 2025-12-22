package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors

/**
 * Distortion/Volume panel with output metering.
 * Layout: 2x2 grid with Drive, Volume in top row; Mix, Peak LED in bottom row.
 */
@Composable
fun DistortionPanel(
    drive: Float, onDriveChange: (Float) -> Unit,
    volume: Float, onVolumeChange: (Float) -> Unit,
    mix: Float, onMixChange: (Float) -> Unit,
    peak: Float,
    modifier: Modifier = Modifier
) {
    CollapsibleColumnPanel(
        title = "VOL",
        color = SongeColors.neonMagenta,
        initialExpanded = true,
        expandedWidth = 200.dp,
        useFlexWidth = true,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header - Centered
            Text(
                text = "Distortion",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SongeColors.neonMagenta
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Top Row: Drive, Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RotaryKnob(
                    value = drive,
                    onValueChange = onDriveChange,
                    label = "DRIVE",
                    controlId = null,
                    size = 56.dp,
                    progressColor = SongeColors.neonMagenta
                )
                RotaryKnob(
                    value = volume,
                    onValueChange = onVolumeChange,
                    label = "VOL",
                    controlId = null,
                    size = 56.dp,
                    progressColor = SongeColors.electricBlue
                )
            }
            
            // Bottom Row: Mix, Peak LED
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = mix,
                    onValueChange = onMixChange,
                    label = "MIX",
                    controlId = null,
                    size = 56.dp,
                    progressColor = SongeColors.warmGlow
                )
                
                // Peak LED indicator
                PeakLed(peak = peak)
            }
        }
    }
}

/**
 * LED indicator that shows peak level with color coding.
 * Blue: Normal (< 0.8)
 * Yellow: Hot (0.8 - 0.95)
 * Red: Clipping (> 0.95)
 */
@Composable
private fun PeakLed(
    peak: Float,
    modifier: Modifier = Modifier
) {
    val ledColor = when {
        peak > 0.95f -> Color.Red
        peak > 0.8f -> Color.Yellow
        else -> SongeColors.electricBlue
    }
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            // Subtle glow
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .blur(8.dp)
                    .background(ledColor.copy(alpha = 0.4f), CircleShape)
            )
            // LED core
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(ledColor, CircleShape)
            )
            // Highlight
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .align(Alignment.TopCenter)
                    .background(Color.White.copy(alpha = 0.4f), CircleShape)
            )
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Text(
            text = "PEAK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = String.format("%.2f", peak),
            style = MaterialTheme.typography.labelMedium,
            color = ledColor
        )
    }
}
