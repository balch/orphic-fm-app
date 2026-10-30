package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal fader for a duo's mod depth (0.0-1.0). Drag to set; the patch bay owns the sources.
 * Mixer-fader style: the thumb is taller than the track and extends above/below it.
 */
@Composable
fun ModDepthFader(
    depth: Float,
    onDepthChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.warmGlow,
    controlId: String? = null
) {
    val density = LocalDensity.current
    val trackWidthDp = 90
    val trackHeightDp = 8
    val thumbWidthDp = 36
    val thumbHeightDp = 28

    val trackWidthPx = with(density) { trackWidthDp.dp.toPx() }
    val thumbWidthPx = with(density) { thumbWidthDp.dp.toPx() }
    val trackHeightPx = with(density) { trackHeightDp.dp.toPx() }
    val usableRange = trackWidthPx - thumbWidthPx

    val learnState = LocalLearnModeState.current
    val baseModifier = if (controlId != null) {
        modifier.learnable(controlId, learnState)
    } else {
        modifier
    }

    val currentOnDepthChange by rememberUpdatedState(onDepthChange)
    val activeAlpha = if (depth <= 0f) 0.35f else 1f

    // Outer box sized to thumb height (taller than track)
    Box(
        modifier = baseModifier
            .width(trackWidthDp.dp)
            .height(thumbHeightDp.dp)
            .drawBehind {
                // Draw the thin track centered vertically
                val trackTop = (size.height - trackHeightPx) / 2f
                val cornerRadius = CornerRadius(4.dp.toPx())

                // Track background
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.08f),
                            color.copy(alpha = 0.18f)
                        )
                    ),
                    topLeft = Offset(0f, trackTop),
                    size = Size(size.width, trackHeightPx),
                    cornerRadius = cornerRadius
                )

                // Track border
                drawRoundRect(
                    color = color.copy(alpha = 0.3f * activeAlpha),
                    topLeft = Offset(0f, trackTop),
                    size = Size(size.width, trackHeightPx),
                    cornerRadius = cornerRadius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                )

                // Progress fill from left edge to thumb center
                val thumbCenterX = thumbWidthPx / 2f + depth * usableRange
                drawRoundRect(
                    color = color.copy(alpha = 0.25f * activeAlpha),
                    topLeft = Offset(0f, trackTop),
                    size = Size(thumbCenterX, trackHeightPx),
                    cornerRadius = cornerRadius
                )
            }
            .pointerInput(usableRange, learnState.isActive) {
                if (learnState.isActive) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()

                    var cumulativeAbsX = 0f
                    var isDragging = false
                    var lastX = down.position.x
                    val touchSlop = viewConfiguration.touchSlop

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        val deltaX = abs(change.position.x - lastX)
                        cumulativeAbsX += deltaX
                        lastX = change.position.x

                        if (!isDragging && cumulativeAbsX > touchSlop) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val newDepth = ((change.position.x - thumbWidthPx / 2f) / usableRange)
                                .coerceIn(0f, 1f)
                            currentOnDepthChange(newDepth)
                        }
                        change.consume()
                    } while (change.pressed)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Thumb — extends above and below the track
        val thumbOffsetX = depth * usableRange
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetX.roundToInt(), 0) }
                .width(thumbWidthDp.dp)
                .height(thumbHeightDp.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            OrpheusColors.metallicHighlight,
                            OrpheusColors.metallicSurface,
                            OrpheusColors.metallicShadow,
                            OrpheusColors.metallicDark
                        )
                    )
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.2f * activeAlpha),
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.width(2.dp).height(14.dp).background(color.copy(alpha = 0.6f * activeAlpha)))
        }
    }
}

@Preview
@Composable
fun ModDepthFaderPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModDepthFader(depth = 0.0f, onDepthChange = {}, color = OrpheusColors.neonMagenta)
            ModDepthFader(depth = 0.5f, onDepthChange = {}, color = OrpheusColors.neonCyan)
            ModDepthFader(depth = 0.8f, onDepthChange = {}, color = OrpheusColors.warmGlow)
        }
    }
}
