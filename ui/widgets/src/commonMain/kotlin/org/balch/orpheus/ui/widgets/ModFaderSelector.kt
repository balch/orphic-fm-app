package org.balch.orpheus.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Combined horizontal fader + mode selector for duo mod controls.
 *
 * - **Drag** horizontally → adjusts mod depth 0.0–1.0
 * - **Tap** (release with minimal movement) → cycles mode OFF→LFO→FM→FLUX→OFF
 *
 * The thumb displays the current mode label with an animated slide transition.
 * Mixer-fader style: the thumb is taller than the track and extends above/below it.
 */
@Composable
fun ModFaderSelector(
    depth: Float,
    onDepthChange: (Float) -> Unit,
    activeSource: ModSource,
    onSourceChange: (ModSource) -> Unit,
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

    // rememberUpdatedState so the long-lived pointerInput coroutine always sees current values
    val currentSource by rememberUpdatedState(activeSource)
    val currentOnSourceChange by rememberUpdatedState(onSourceChange)
    val currentOnDepthChange by rememberUpdatedState(onDepthChange)

    val isOff = activeSource == ModSource.OFF
    val activeAlpha = if (isOff) 0.35f else 1f

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

                    // Released — if no drag happened, it's a tap → cycle mode
                    if (!isDragging && cumulativeAbsX < touchSlop) {
                        val next = when (currentSource) {
                            ModSource.OFF -> ModSource.LFO
                            ModSource.LFO -> ModSource.VOICE_FM
                            ModSource.VOICE_FM -> ModSource.FLUX
                            ModSource.FLUX -> ModSource.OFF
                        }
                        currentOnSourceChange(next)
                    }
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
            AnimatedContent(
                targetState = activeSource,
                transitionSpec = {
                    (slideInVertically { height -> height } + fadeIn()).togetherWith(
                        slideOutVertically { height -> -height } + fadeOut()
                    )
                },
                label = "ModFaderLabel"
            ) { source ->
                val label = when (source) {
                    ModSource.OFF -> "OFF"
                    ModSource.LFO -> "LFO"
                    ModSource.VOICE_FM -> "FM"
                    ModSource.FLUX -> "FLUX"
                }
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (source == ModSource.OFF) color.copy(alpha = 0.5f) else color,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview
@Composable
fun ModFaderSelectorPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModFaderSelector(
                depth = 0.0f,
                onDepthChange = {},
                activeSource = ModSource.OFF,
                onSourceChange = {},
                color = OrpheusColors.neonMagenta
            )
            ModFaderSelector(
                depth = 0.5f,
                onDepthChange = {},
                activeSource = ModSource.LFO,
                onSourceChange = {},
                color = OrpheusColors.neonCyan
            )
            ModFaderSelector(
                depth = 0.8f,
                onDepthChange = {},
                activeSource = ModSource.VOICE_FM,
                onSourceChange = {},
                color = OrpheusColors.warmGlow
            )
        }
    }
}
