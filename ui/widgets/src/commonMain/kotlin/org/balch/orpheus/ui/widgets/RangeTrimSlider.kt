package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.abs

private enum class DragTarget { LEFT, RIGHT, WINDOW }

/**
 * Dual-handle range trim slider.
 * Drag left/right edges to resize the window, or drag the middle to slide it.
 *
 * @param min Current low bound (0..1)
 * @param max Current high bound (0..1)
 * @param onRangeChange Called with (newMin, newMax) during drag
 * @param color Accent color for the active window region
 * @param trackWidth Width of the track in dp
 */
@Composable
fun RangeTrimSlider(
    min: Float,
    max: Float,
    onRangeChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan,
    trackWidth: Int = 120,
) {
    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackWidth.dp.toPx() }
    val handleWidth = with(density) { 6.dp.toPx() }
    val trackHeight = 18.dp
    val minWindowPx = handleWidth * 2  // minimum window size

    val currentOnRangeChange by rememberUpdatedState(onRangeChange)
    val currentMin by rememberUpdatedState(min)
    val currentMax by rememberUpdatedState(max)
    var dragTarget by remember { mutableStateOf<DragTarget?>(null) }
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartMin by remember { mutableStateOf(0f) }
    var dragStartMax by remember { mutableStateOf(0f) }

    val leftPx = min * trackWidthPx
    val rightPx = max * trackWidthPx

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 2.dp)
            .width(trackWidth.dp)
            .height(trackHeight)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(trackWidthPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: continue

                        when (event.type) {
                            PointerEventType.Press -> {
                                if (event.changes.any { it.pressed }) {
                                    val x = pos.x
                                    val lx = currentMin * trackWidthPx
                                    val rx = currentMax * trackWidthPx
                                    // Determine what was grabbed: left handle, right handle, or window
                                    dragTarget = when {
                                        abs(x - lx) < handleWidth * 2 -> DragTarget.LEFT
                                        abs(x - rx) < handleWidth * 2 -> DragTarget.RIGHT
                                        x in lx..rx -> DragTarget.WINDOW
                                        x < lx -> DragTarget.LEFT
                                        else -> DragTarget.RIGHT
                                    }
                                    dragStartX = x
                                    dragStartMin = currentMin
                                    dragStartMax = currentMax
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            PointerEventType.Move -> {
                                if (event.changes.any { it.pressed } && dragTarget != null) {
                                    val x = pos.x
                                    val delta = (x - dragStartX) / trackWidthPx

                                    when (dragTarget) {
                                        DragTarget.LEFT -> {
                                            val newMin = (dragStartMin + delta)
                                                .coerceIn(0f, dragStartMax - minWindowPx / trackWidthPx)
                                            currentOnRangeChange(newMin, dragStartMax)
                                        }
                                        DragTarget.RIGHT -> {
                                            val newMax = (dragStartMax + delta)
                                                .coerceIn(dragStartMin + minWindowPx / trackWidthPx, 1f)
                                            currentOnRangeChange(dragStartMin, newMax)
                                        }
                                        DragTarget.WINDOW -> {
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
                val h = size.height
                val lx = min * size.width
                val rx = max * size.width

                // Inactive regions (dark)
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset.Zero,
                    size = Size(lx, h)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(rx, 0f),
                    size = Size(size.width - rx, h)
                )

                // Active window
                drawRect(
                    color = color.copy(alpha = 0.15f),
                    topLeft = Offset(lx, 0f),
                    size = Size(rx - lx, h)
                )

                // Left handle
                drawRoundRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(lx, 2f),
                    size = Size(handleWidth, h - 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f)
                )

                // Right handle
                drawRoundRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(rx - handleWidth, 2f),
                    size = Size(handleWidth, h - 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f)
                )

                // Top/bottom edge lines on active window
                drawLine(color.copy(alpha = 0.4f), Offset(lx, 0f), Offset(rx, 0f), 1f)
                drawLine(color.copy(alpha = 0.4f), Offset(lx, h), Offset(rx, h), 1f)
            }
    )
}

@Preview
@Composable
fun RangeTrimSliderPreview() {
    OrpheusTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeTrimSlider(
                min = 0.2f,
                max = 0.8f,
                onRangeChange = { _, _ -> },
                color = OrpheusColors.neonCyan
            )
            RangeTrimSlider(
                min = 0.0f,
                max = 0.5f,
                onRangeChange = { _, _ -> },
                color = OrpheusColors.warmGlow,
                trackWidth = 80
            )
        }
    }
}
