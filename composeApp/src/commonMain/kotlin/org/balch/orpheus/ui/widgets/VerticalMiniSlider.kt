package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Vertical mini slider for compact mobile layouts.
 * 
 * For envelope speed: topLabel = "S" (slow), bottomLabel = "F" (fast)
 * For tune: topLabel = "+" (higher), bottomLabel = "-" (lower)
 * 
 * Top = 1.0, Bottom = 0.0
 */
@Composable
fun VerticalMiniSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan,
    topLabel: String = "",
    bottomLabel: String = "",
    trackHeight: Int = 50,
    thumbSize: Int = 10,
    controlId: String? = null
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { trackHeight.dp.toPx() }
    val thumbSizePx = with(density) { thumbSize.dp.toPx() }
    val usableRange = trackHeightPx - thumbSizePx

    // Invert value for visual: top = 1, bottom = 0
    var offsetY by remember(value) { mutableFloatStateOf((1f - value) * usableRange) }

    val finalModifier = if (controlId != null) {
        modifier.learnable(controlId, LocalLearnModeState.current)
    } else {
        modifier
    }

    Column(
        modifier = finalModifier
            .clip(RoundedCornerShape(4.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Top label - clickable to snap to 1
        if (topLabel.isNotEmpty()) {
            Text(
                topLabel,
                fontSize = 6.sp,
                color = if (value > 0.7f) color else color.copy(alpha = 0.5f),
                fontWeight = if (value > 0.7f) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 8.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable {
                        offsetY = 0f
                        onValueChange(1f)
                    }
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }

        // Track with thumb
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(trackHeight.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.3f),
                            color.copy(alpha = 0.1f)
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
                                            (position.y - thumbSizePx / 2).coerceIn(0f, usableRange)
                                        offsetY = newOffset
                                        // Invert for value: top = 1, bottom = 0
                                        onValueChange(1f - (newOffset / usableRange))
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
                    .offset { IntOffset(0, offsetY.roundToInt()) }
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 1.dp)
                    .size(20.dp, thumbSize.dp)
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

        // Bottom label - clickable to snap to 0
        if (bottomLabel.isNotEmpty()) {
            Text(
                bottomLabel,
                fontSize = 6.sp,
                color = if (value < 0.3f) color else color.copy(alpha = 0.5f),
                fontWeight = if (value < 0.3f) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 8.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable {
                        offsetY = usableRange
                        onValueChange(0f)
                    }
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }
    }
}

@Preview
@Composable
fun VerticalMiniSliderPreview() {
    OrpheusTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VerticalMiniSlider(
                value = 0.5f,
                onValueChange = {},
                topLabel = "S",
                bottomLabel = "F",
                color = OrpheusColors.neonCyan
            )
            VerticalMiniSlider(
                value = 0.8f,
                onValueChange = {},
                topLabel = "+",
                bottomLabel = "-",
                color = OrpheusColors.warmGlow
            )
        }
    }
}
