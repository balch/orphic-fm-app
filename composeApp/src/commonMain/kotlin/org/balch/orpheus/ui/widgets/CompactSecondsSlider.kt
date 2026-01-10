package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

/**
 * Compact seconds slider derived from HorizontalEnvelopeSlider.
 * Used for sequencer duration selection (10s to 120s).
 */
@Composable
fun CompactSecondsSlider(
    valueSeconds: Float, // Current duration in seconds
    valueRange: ClosedFloatingPointRange<Float> = 10f..120f,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan,
    thumbSize: Int = 10   // dp
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val thumbSizePx = with(density) { thumbSize.dp.toPx() }

    // Calculate normalized value (0..1)
    val normalizedValue = (valueSeconds - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    
    // Calculate visual offset
    val usableWidth = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)
    var offsetX = normalizedValue * usableWidth

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Min Label
        Text(
            "${valueRange.start.toInt()}s",
            fontSize = 10.sp,
            color = if (valueSeconds <= valueRange.start + 5) color else color.copy(alpha = 0.5f),
            fontWeight = if (valueSeconds <= valueRange.start + 5) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .clickable { onValueChange(valueRange.start) }
        )

        // Track with thumb
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
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
                .pointerInput(usableWidth) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.firstOrNull()?.position ?: continue

                            when (event.type) {
                                PointerEventType.Press, PointerEventType.Move -> {
                                    if (event.changes.any { it.pressed } && usableWidth > 0) {
                                        val newOffset = (position.x - thumbSizePx / 2).coerceIn(0f, usableWidth)
                                        val newNormalized = newOffset / usableWidth
                                        val newValue = valueRange.start + (newNormalized * (valueRange.endInclusive - valueRange.start))
                                        onValueChange(newValue)
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

        // Max Label
        Text(
            "${valueRange.endInclusive.toInt()}s",
            fontSize = 10.sp,
            color = if (valueSeconds >= valueRange.endInclusive - 5) color else color.copy(alpha = 0.5f),
            fontWeight = if (valueSeconds >= valueRange.endInclusive - 5) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .clickable { onValueChange(valueRange.endInclusive) }
        )
    }
}

@Preview
@Composable
private fun CompactSecondsSliderPreview() {
    Column(
        modifier = Modifier.background(OrpheusColors.darkVoid).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CompactSecondsSlider(
            valueSeconds = 30f,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth()
        )
        CompactSecondsSlider(
            valueSeconds = 90f,
            onValueChange = {},
            color = OrpheusColors.neonMagenta,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
