package org.balch.songe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

/**
 * Horizontal slider for envelope speed (attack/decay).
 * 
 * Left (F) = Fast/Percussive, Right (S) = Slow/Drone
 * Responds immediately on mouse down and supports drag.
 */
@Composable
fun HorizontalEnvelopeSlider(
    value: Float, // 0 = Fast, 1 = Slow
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SongeColors.neonCyan,
    trackWidth: Int = 44, // dp
    thumbSize: Int = 10   // dp
) {
    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackWidth.dp.toPx() }
    val thumbSizePx = with(density) { thumbSize.dp.toPx() }
    val usableRange = trackWidthPx - thumbSizePx
    
    var offsetX by remember(value) { mutableFloatStateOf(value * usableRange) }
    
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Left label (F = Fast) - clickable to snap to 0
        Text(
            "F",
            fontSize = 7.sp,
            color = if (value < 0.3f) color else color.copy(alpha = 0.5f),
            fontWeight = if (value < 0.3f) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable { 
                    offsetX = 0f
                    onValueChange(0f) 
                }
                .padding(horizontal = 2.dp, vertical = 2.dp)
        )
        
        // Track with thumb - responds on press and drag
        Box(
            modifier = Modifier
                .width(trackWidth.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.1f),
                            color.copy(alpha = 0.3f)
                        )
                    )
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.firstOrNull()?.position ?: continue
                            
                            when (event.type) {
                                PointerEventType.Press, PointerEventType.Move -> {
                                    if (event.changes.any { it.pressed }) {
                                        val newOffset = (position.x - thumbSizePx / 2).coerceIn(0f, usableRange)
                                        offsetX = newOffset
                                        onValueChange(newOffset / usableRange)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Thumb
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .align(Alignment.CenterStart)
                    .padding(vertical = 1.dp)
                    .size(thumbSize.dp, 10.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                color,
                                color.copy(alpha = 0.7f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
        }
        
        // Right label (S = Slow) - clickable to snap to 1
        Text(
            "S",
            fontSize = 7.sp,
            color = if (value > 0.7f) color else color.copy(alpha = 0.5f),
            fontWeight = if (value > 0.7f) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable { 
                    offsetX = usableRange
                    onValueChange(1f) 
                }
                .padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
}

@Preview
@Composable
fun HorizontalEnvelopeSliderPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalEnvelopeSlider(
            value = 0f,
            onValueChange = {},
            color = SongeColors.neonCyan
        )
        HorizontalEnvelopeSlider(
            value = 0.5f,
            onValueChange = {},
            color = SongeColors.warmGlow
        )
        HorizontalEnvelopeSlider(
            value = 1f,
            onValueChange = {},
            color = SongeColors.neonMagenta
        )
    }
}

