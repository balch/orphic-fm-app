package org.balch.songe.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.songe.ui.theme.SongeColors

/**
 * A momentary push button that generates a pulse.
 * The longer it's held, the stronger the pulse visual becomes.
 */
@Composable
@Preview
fun PulseButtonPreview() {
    MaterialTheme {
        PulseButton(onPulseStart = {}, onPulseEnd = {})
    }
}

@Composable
fun PulseButton(
    onPulseStart: () -> Unit,
    onPulseEnd: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "PULSE",
    size: Dp = 48.dp,
    activeColor: Color = SongeColors.neonMagenta
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Logic to trigger start/end callbacks
    LaunchedEffect(isPressed) {
        if (isPressed) {
            onPulseStart()
        } else {
            onPulseEnd()
        }
    }
    
    // Animate glow size based on press state
    val glowAlpha by animateFloatAsState(targetValue = if (isPressed) 0.8f else 0f)
    val glowRadius by animateFloatAsState(targetValue = if (isPressed) 10f else 0f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null, // Custom drawing handles feedback
                    onClick = {} 
                )
                .drawBehind {
                    val radius = size.toPx() / 2
                    
                    // 1. Touch Glow (Underneath)
                     if (glowAlpha > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(activeColor.copy(alpha = glowAlpha), Color.Transparent),
                                center = center,
                                radius = radius + glowRadius.dp.toPx() + 10f
                            ),
                            radius = radius + glowRadius.dp.toPx() + 10f
                        )
                    }

                    // 2. Metal Contact Body (Sensor)
                    // Silver/Steel look
                    val metalGradient = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFE0E0E0), // Bright Silver
                            Color(0xFFA0A0A0), // Standard Steel
                            Color(0xFF505050)  // Dark Shadow
                        ),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius)
                    )
                    
                    drawCircle(
                        brush = metalGradient,
                        radius = radius
                    )
                    
                    // 3. Inner Detail (Concentric rings to look like machined metal)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.2f),
                        radius = radius * 0.8f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.2f),
                        radius = radius * 0.5f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )

                    // 4. Pressed Overlay (Darkens when touched)
                    if (isPressed) {
                         drawCircle(
                             color = Color.Black.copy(alpha = 0.3f),
                             radius = radius
                         )
                    }
                }
        )
    }
}
