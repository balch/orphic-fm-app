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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.roundToInt

/**
 * Generic horizontal fader with the metallic mixer-thumb style from [ModFaderSelector].
 *
 * - **Drag** horizontally to adjust [value] in 0.0–1.0.
 * - Optional [thumbLabel] text is rendered inside the metallic thumb.
 * - Optional [rightLabel] text appears to the right of the track.
 * - Optional [secondaryRange] draws a dim highlight on the track between the
 *   two normalized endpoints (0.0–1.0), useful for showing valid bounds.
 */
@Composable
fun HorizontalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.warmGlow,
    thumbLabel: String = "",
    rightLabel: String = "",
    trackWidth: Int = 90,
    enabled: Boolean = true,
    secondaryRange: ClosedFloatingPointRange<Float>? = null,
) {
    val density = LocalDensity.current
    val trackHeightDp = 8
    val thumbWidthDp = 14
    val thumbHeightDp = 24

    val trackWidthPx = with(density) { trackWidth.dp.toPx() }
    val thumbWidthPx = with(density) { thumbWidthDp.dp.toPx() }
    val trackHeightPx = with(density) { trackHeightDp.dp.toPx() }
    val usableRange = trackWidthPx - thumbWidthPx

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val activeAlpha = if (enabled) 1f else 0.35f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = modifier
                .width(trackWidth.dp)
                .height(thumbHeightDp.dp)
                .drawBehind {
                    val trackTop = (size.height - trackHeightPx) / 2f
                    val cornerRadius = CornerRadius(4.dp.toPx())

                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                color.copy(alpha = 0.08f),
                                color.copy(alpha = 0.18f),
                            )
                        ),
                        topLeft = Offset(0f, trackTop),
                        size = Size(size.width, trackHeightPx),
                        cornerRadius = cornerRadius,
                    )
                    drawRoundRect(
                        color = color.copy(alpha = 0.3f * activeAlpha),
                        topLeft = Offset(0f, trackTop),
                        size = Size(size.width, trackHeightPx),
                        cornerRadius = cornerRadius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                    )
                    if (secondaryRange != null) {
                        val sStart = thumbWidthPx / 2f + secondaryRange.start * usableRange
                        val sEnd = thumbWidthPx / 2f + secondaryRange.endInclusive * usableRange
                        drawRoundRect(
                            color = color.copy(alpha = 0.35f * activeAlpha),
                            topLeft = Offset(sStart, trackTop),
                            size = Size((sEnd - sStart).coerceAtLeast(0f), trackHeightPx),
                            cornerRadius = cornerRadius,
                        )
                    }
                    val thumbCenterX = thumbWidthPx / 2f + value * usableRange
                    drawRoundRect(
                        color = color.copy(alpha = 0.25f * activeAlpha),
                        topLeft = Offset(0f, trackTop),
                        size = Size(thumbCenterX, trackHeightPx),
                        cornerRadius = cornerRadius,
                    )
                }
                .then(
                    if (enabled) {
                        Modifier.pointerInput(usableRange) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    val newValue =
                                        ((change.position.x - thumbWidthPx / 2f) / usableRange)
                                            .coerceIn(0f, 1f)
                                    currentOnValueChange(newValue)
                                    change.consume()
                                } while (change.pressed)
                            }
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbOffsetX = value * usableRange
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
                                OrpheusColors.metallicDark,
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.2f * activeAlpha),
                        RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbLabel.isNotEmpty()) {
                    Text(
                        text = thumbLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = color.copy(alpha = activeAlpha),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (rightLabel.isNotEmpty()) {
            Text(
                text = rightLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = activeAlpha),
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Preview
@Composable
private fun HorizontalFaderPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalFader(
                value = 0.35f,
                onValueChange = {},
                color = OrpheusColors.cosmicPurple,
                rightLabel = "350ms",
            )
            HorizontalFader(
                value = 0.8f,
                onValueChange = {},
                color = OrpheusColors.neonCyan,
                thumbLabel = "DJ",
                rightLabel = "800ms",
            )
        }
    }
}
