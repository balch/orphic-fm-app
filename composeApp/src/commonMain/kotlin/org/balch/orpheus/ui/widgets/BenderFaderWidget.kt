package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Old-school mixer fader style slider with spring-back animation for the bender effect.
 * 
 * The slider is vertically oriented with the thumb centered in the middle.
 * - Drag up: positive bend (pitch up)
 * - Drag down: negative bend (pitch down)
 * - Release: spring animates back to center with exaggerated oscillation
 * 
 * Inspired by classic analog mixer faders:
 * - Narrow metallic track
 * - Wide rectangular thumb handle with center groove
 * - Tension indicator (color/glow intensity based on displacement)
 */
@Composable
fun BenderFaderWidget(
    value: Float, // Current bend value from state (-1 to +1)
    onValueChange: (Float) -> Unit, // Called continuously during drag
    onRelease: () -> Unit = {}, // Called when finger releases
    modifier: Modifier = Modifier,
    trackHeight: Int = 144, // 20% taller than 120
    trackWidth: Int = 12, // Narrow track like mixer faders
    thumbWidth: Int = 52, // Wider thumb
    thumbHeight: Int = 28, // Slightly taller thumb
    accentColor: Color = OrpheusColors.neonCyan,
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { trackHeight.dp.toPx() }
    val thumbHeightPx = with(density) { thumbHeight.dp.toPx() }
    val usableRange = (trackHeightPx - thumbHeightPx) / 2f // Half range from center
    
    val coroutineScope = rememberCoroutineScope()
    
    // Animation state for spring-back
    val animatedOffset = remember { Animatable(0f) }
    
    // Track if we're currently in a local interaction (dragging or spring-back animation)
    // When true, we own the animation and should not sync from external values
    var isLocallyAnimating by remember { mutableFloatStateOf(0f) } // 0 = idle, non-zero = local control
    
    // Sync animated offset with external value only when we're not actively controlling it
    // This handles AI-driven bends while preventing the external sync from interrupting
    // our spring-back animation
    LaunchedEffect(value) {
        if (isLocallyAnimating == 0f) {
            animatedOffset.snapTo(-value * usableRange) // Negate: top = positive
        }
    }
    
    // Calculate tension (0 to 1) based on displacement
    val tension = (animatedOffset.value.absoluteValue / usableRange).coerceIn(0f, 1f)
    
    // Tension-based color: from accent to warning color at extremes
    val tensionColor = Color(
        red = (accentColor.red + (OrpheusColors.warmGlow.red - accentColor.red) * tension),
        green = (accentColor.green + (OrpheusColors.warmGlow.green - accentColor.green) * tension),
        blue = (accentColor.blue + (OrpheusColors.warmGlow.blue - accentColor.blue) * tension),
        alpha = 1f
    )
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Direction indicator: up arrow when pulling up
        Text(
            text = if (animatedOffset.value < -10) "↑" else "",
            fontSize = 10.sp,
            color = tensionColor.copy(alpha = tension),
            fontWeight = FontWeight.Bold
        )
        
        // Wide touch target containing narrow track and wide thumb
        Box(
            modifier = Modifier
                .width(thumbWidth.dp + 16.dp) // Touch area wide enough for thumb
                .height(trackHeight.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var startY = 0f
                    var startOffset = 0f
                    
                    detectDragGestures(
                        onDragStart = { offset ->
                            isLocallyAnimating = 1f
                            startY = offset.y
                            startOffset = animatedOffset.value
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val delta = change.position.y - startY
                            val newOffset = (startOffset + delta).coerceIn(-usableRange, usableRange)
                            coroutineScope.launch {
                                animatedOffset.snapTo(newOffset)
                            }
                            // Convert to value: negate because up = positive
                            val newValue = -(newOffset / usableRange)
                            onValueChange(newValue.coerceIn(-1f, 1f))
                        },
                        onDragEnd = {
                            // Keep isLocallyAnimating = 1f during the spring-back animation
                            // to prevent external value syncing from interrupting
                            
                            // Calculate spring duration based on how far back it was pulled
                            // ~1.2s at full extension, shorter for smaller pulls
                            val pullDistance = animatedOffset.value.absoluteValue / usableRange
                            val springDuration = (400 + (pullDistance * 800)).toInt() // 400ms to 1200ms
                            
                            // Spring back to center with exaggerated oscillation
                            coroutineScope.launch {
                                // Use a custom animation with overshoot for "unnatural" spring feel
                                // Overshoot the target, then settle back
                                val startValue = animatedOffset.value
                                val overshootAmount = -startValue * 0.3f // 30% overshoot in opposite direction
                                
                                // First: animate past center (overshoot)
                                animatedOffset.animateTo(
                                    targetValue = overshootAmount,
                                    animationSpec = tween(
                                        durationMillis = (springDuration * 0.4f).toInt(),
                                        easing = { t -> t * (2 - t) } // Ease out quad
                                    )
                                )
                                // Update value during overshoot
                                val overshootValue = -(animatedOffset.value / usableRange)
                                onValueChange(overshootValue.coerceIn(-1f, 1f))
                                
                                // Second: bounce back past center again (smaller)
                                animatedOffset.animateTo(
                                    targetValue = -overshootAmount * 0.4f,
                                    animationSpec = tween(
                                        durationMillis = (springDuration * 0.3f).toInt(),
                                        easing = { t -> t * (2 - t) }
                                    )
                                )
                                onValueChange(-(animatedOffset.value / usableRange).coerceIn(-1f, 1f))
                                
                                // Third: settle to center
                                animatedOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = (springDuration * 0.3f).toInt(),
                                        easing = { t -> t * (2 - t) }
                                    )
                                )
                                
                                onRelease()
                                onValueChange(0f) // Ensure final value is zero
                                
                                // NOW we're done with local animation, allow external sync again
                                isLocallyAnimating = 0f
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                animatedOffset.animateTo(0f, tween(300))
                                onRelease()
                                onValueChange(0f)
                                isLocallyAnimating = 0f
                            }
                        }
                    )
                }
        ) {
            // Narrow track (centered in touch area)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(trackWidth.dp)
                    .height(trackHeight.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF1A1A1A),
                                Color(0xFF3A3A3A),
                                Color(0xFF2A2A2A),
                                Color(0xFF1A1A1A)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .drawBehind {
                        // Draw center notch/mark
                        val centerY = size.height / 2f
                        drawLine(
                            color = accentColor.copy(alpha = 0.6f),
                            start = Offset(0f, centerY),
                            end = Offset(size.width, centerY),
                            strokeWidth = 2f
                        )
                        
                        // Draw subtle tick marks
                        val tickSpacing = size.height / 8
                        for (i in 1..7) {
                            if (i == 4) continue // Skip center
                            val y = tickSpacing * i
                            drawLine(
                                color = Color.White.copy(alpha = 0.1f),
                                start = Offset(2f, y),
                                end = Offset(size.width - 2f, y),
                                strokeWidth = 1f
                            )
                        }
                        
                        // Draw tension indicator bar from center
                        if (tension > 0.05f) {
                            val barHeight = animatedOffset.value.absoluteValue
                            val barTop = if (animatedOffset.value < 0) centerY - barHeight else centerY
                            drawRoundRect(
                                color = tensionColor.copy(alpha = 0.4f),
                                topLeft = Offset(2f, barTop),
                                size = Size(size.width - 4f, barHeight),
                                cornerRadius = CornerRadius(2f, 2f)
                            )
                        }
                    }
            )
            
            // Wide thumb handle (mixer fader style)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(0, animatedOffset.value.roundToInt()) }
                    .shadow(
                        elevation = (4 + tension * 4).dp,
                        shape = RoundedCornerShape(4.dp),
                        ambientColor = tensionColor,
                        spotColor = tensionColor
                    )
                    .size(thumbWidth.dp, thumbHeight.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF505060), // Metallic top highlight
                                Color(0xFF404050),
                                Color(0xFF353545),
                                Color(0xFF252530)  // Darker bottom
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                    .drawBehind {
                        // Draw center groove (classic mixer fader style)
                        val centerX = size.width / 2f
                        val grooveWidth = 4f
                        val grooveDepth = 3f
                        
                        // Groove shadow (dark indent)
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            topLeft = Offset(centerX - grooveWidth / 2 - 1, 4f),
                            size = Size(grooveWidth + 2, size.height - 8f),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                        
                        // Groove highlight (the actual groove)
                        drawRoundRect(
                            color = tensionColor.copy(alpha = 0.6f + tension * 0.4f),
                            topLeft = Offset(centerX - grooveWidth / 2, 5f),
                            size = Size(grooveWidth, size.height - 10f),
                            cornerRadius = CornerRadius(1f, 1f)
                        )
                        
                        // Subtle edge highlights
                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(4f, 4f),
                            end = Offset(4f, size.height - 4f),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(size.width - 4f, 4f),
                            end = Offset(size.width - 4f, size.height - 4f),
                            strokeWidth = 1f
                        )
                    }
            )
        }
        
        // Direction indicator: down arrow when pulling down  
        Text(
            text = if (animatedOffset.value > 10) "↓" else "",
            fontSize = 10.sp,
            color = tensionColor.copy(alpha = tension),
            fontWeight = FontWeight.Bold
        )
    }
}

@Preview
@Composable
private fun BenderSliderPreview() {
    OrpheusTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "BEND",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.neonCyan
            )
            BenderFaderWidget(
                value = 0f,
                onValueChange = {},
                onRelease = {},
                accentColor = OrpheusColors.neonCyan
            )
        }
    }
}

@Preview
@Composable
private fun BenderSliderPulledPreview() {
    OrpheusTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "BEND (pulled)",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.warmGlow
            )
            // This preview shows the slider at -0.7 position (pulled down)
            BenderFaderWidget(
                value = -0.7f,
                onValueChange = {},
                onRelease = {},
                accentColor = OrpheusColors.warmGlow
            )
        }
    }
}
