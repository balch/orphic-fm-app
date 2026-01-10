package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

/**
 * Horizontal mini slider for compact mobile layouts.
 * 
 * Left = 0.0, Right = 1.0
 */
@Composable
fun HorizontalMiniSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan,
    leftLabel: String = "",
    rightLabel: String = "",
    trackWidth: Int = 50,
    thumbSize: Int = 10,
    controlId: String? = null
) {
    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackWidth.dp.toPx() }
    val thumbSizePx = with(density) { thumbSize.dp.toPx() }
    val usableRange = trackWidthPx - thumbSizePx

    // visual: left = 0, right = 1
    var offsetX by remember(value) { mutableFloatStateOf(value * usableRange) }

    val finalModifier = if (controlId != null) {
        modifier.learnable(controlId, LocalLearnModeState.current)
    } else {
        modifier
    }

    Row(
        modifier = finalModifier
            .clip(RoundedCornerShape(4.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Left label - clickable to snap to 0
        if (leftLabel.isNotEmpty()) {
            Text(
                leftLabel,
                fontSize = 6.sp,
                color = if (value < 0.3f) color else color.copy(alpha = 0.5f),
                fontWeight = if (value < 0.3f) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 8.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable {
                        offsetX = 0f
                        onValueChange(0f)
                    }
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }

        // Track with thumb
        Box(
            modifier = Modifier
                .width(trackWidth.dp)
                .height(16.dp)
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
                                        val newOffset =
                                            (position.x - thumbSizePx / 2).coerceIn(0f, usableRange)
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
                    .size(thumbSize.dp, 20.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                color.copy(alpha = 0.7f),
                                color
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
        }

        // Right label - clickable to snap to 1
        if (rightLabel.isNotEmpty()) {
            Text(
                rightLabel,
                fontSize = 6.sp,
                color = if (value > 0.7f) color else color.copy(alpha = 0.5f),
                fontWeight = if (value > 0.7f) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 8.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable {
                        offsetX = usableRange
                        onValueChange(1f)
                    }
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }
    }
}

@Preview
@Composable
fun HorizontalMiniSliderPreview() {
    OrpheusTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalMiniSlider(
                value = 0.5f,
                onValueChange = {},
                leftLabel = "0",
                rightLabel = "1",
                color = OrpheusColors.neonCyan
            )
            HorizontalMiniSlider(
                value = 0.8f,
                onValueChange = {},
                leftLabel = "-",
                rightLabel = "+",
                color = OrpheusColors.warmGlow
            )
        }
    }
}
