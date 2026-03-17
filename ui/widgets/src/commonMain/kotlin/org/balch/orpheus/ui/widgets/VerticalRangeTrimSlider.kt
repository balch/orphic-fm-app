package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.abs

private enum class VDragTarget { TOP, BOTTOM, WINDOW }

/**
 * Vertical dual-handle range trim slider (attenuator).
 * Top = max, bottom = min. Drag edges to resize, drag middle to slide.
 * Fills available height from its container via [fillMaxHeight].
 *
 * @param min Current low bound (0..1, bottom)
 * @param max Current high bound (0..1, top)
 * @param onRangeChange Called with (newMin, newMax) during drag
 * @param color Accent color for the active window region
 * @param trackHeight Height of the track in dp (default 80)
 * @param topLabel Single character label above the slider
 * @param bottomLabel Single character label below the slider
 */
@Composable
fun VerticalRangeTrimSlider(
    min: Float,
    max: Float,
    onRangeChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan,
    trackHeight: Int = 80,
    topLabel: String = "+",
    bottomLabel: String = "-",
) {
    val handleHeight = 6f // dp, used for drawing; px computed inside pointerInput
    val trackWidth = 18.dp

    val currentOnRangeChange by rememberUpdatedState(onRangeChange)
    val currentMin by rememberUpdatedState(min)
    val currentMax by rememberUpdatedState(max)
    var dragTarget by remember { mutableStateOf<VDragTarget?>(null) }
    var dragStartY by remember { mutableStateOf(0f) }
    var dragStartMin by remember { mutableStateOf(0f) }
    var dragStartMax by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            topLabel,
            fontSize = 8.sp,
            color = color.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp,
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.panelBackground)
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(vertical = 2.dp)
                .width(trackWidth)
                .height(trackHeight.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    val trackHeightPx = size.height.toFloat()
                    val handlePx = handleHeight * density
                    val minWindowPx = handlePx * 2

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position ?: continue

                            when (event.type) {
                                PointerEventType.Press -> {
                                    if (event.changes.any { it.pressed }) {
                                        val y = pos.y
                                        val topPx = (1f - currentMax) * trackHeightPx
                                        val botPx = (1f - currentMin) * trackHeightPx
                                        dragTarget = when {
                                            abs(y - topPx) < handlePx * 2 -> VDragTarget.TOP
                                            abs(y - botPx) < handlePx * 2 -> VDragTarget.BOTTOM
                                            y in topPx..botPx -> VDragTarget.WINDOW
                                            y < topPx -> VDragTarget.TOP
                                            else -> VDragTarget.BOTTOM
                                        }
                                        dragStartY = y
                                        dragStartMin = currentMin
                                        dragStartMax = currentMax
                                        event.changes.forEach { it.consume() }
                                    }
                                }

                                PointerEventType.Move -> {
                                    if (event.changes.any { it.pressed } && dragTarget != null) {
                                        val y = pos.y
                                        val delta = -(y - dragStartY) / trackHeightPx

                                        when (dragTarget) {
                                            VDragTarget.TOP -> {
                                                val newMax = (dragStartMax + delta)
                                                    .coerceIn(dragStartMin + minWindowPx / trackHeightPx, 1f)
                                                currentOnRangeChange(dragStartMin, newMax)
                                            }
                                            VDragTarget.BOTTOM -> {
                                                val newMin = (dragStartMin + delta)
                                                    .coerceIn(0f, dragStartMax - minWindowPx / trackHeightPx)
                                                currentOnRangeChange(newMin, dragStartMax)
                                            }
                                            VDragTarget.WINDOW -> {
                                                val windowSize = dragStartMax - dragStartMin
                                                var newMin = dragStartMin + delta
                                                var newMax = newMin + windowSize
                                                if (newMin < 0f) { newMin = 0f; newMax = windowSize }
                                                if (newMax > 1f) { newMax = 1f; newMin = 1f - windowSize }
                                                currentOnRangeChange(newMin, newMax)
                                            }
                                            null -> {}
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }

                                PointerEventType.Release -> {
                                    dragTarget = null
                                }

                                else -> {}
                            }
                        }
                    }
                }
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val handlePx = handleHeight * density
                    val topY = (1f - max) * h
                    val botY = (1f - min) * h

                    // Inactive regions
                    drawRect(Color.Black.copy(alpha = 0.4f), Offset.Zero, Size(w, topY))
                    drawRect(Color.Black.copy(alpha = 0.4f), Offset(0f, botY), Size(w, h - botY))

                    // Active window
                    drawRect(color.copy(alpha = 0.15f), Offset(0f, topY), Size(w, botY - topY))

                    // Top handle (max)
                    drawRoundRect(color.copy(alpha = 0.9f), Offset(2f, topY), Size(w - 4f, handlePx), CornerRadius(2f))

                    // Bottom handle (min)
                    drawRoundRect(color.copy(alpha = 0.9f), Offset(2f, botY - handlePx), Size(w - 4f, handlePx), CornerRadius(2f))

                    // Side edge lines
                    drawLine(color.copy(alpha = 0.4f), Offset(0f, topY), Offset(0f, botY), 1f)
                    drawLine(color.copy(alpha = 0.4f), Offset(w, topY), Offset(w, botY), 1f)
                }
        )

        Text(
            bottomLabel,
            fontSize = 8.sp,
            color = color.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp,
        )
    }
}

@Preview(widthDp = 60, heightDp = 150)
@Composable
fun VerticalRangeTrimSliderPreview() {
    OrpheusTheme {
        VerticalRangeTrimSlider(
            min = 0.2f,
            max = 0.8f,
            onRangeChange = { _, _ -> },
            color = OrpheusColors.neonCyan,
            trackHeight = 80,
        )
    }
}
