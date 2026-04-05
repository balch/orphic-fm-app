package org.balch.orpheus.features.timer

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * HH:MM flip clock display using four [FlipDigit]s with a pulsing colon separator.
 *
 * When [isScrollable] is true and [onDurationChange] is provided, the hours and minutes digit
 * pairs respond to vertical drag gestures: drag up to increase, drag down to decrease.
 * Hours change by 60-minute steps; minutes change by 1-minute steps. Clamped to 0–260 minutes.
 *
 * @param remainingSeconds Countdown value to display (converted to HH:MM internally).
 * @param isRunning        Whether the timer is running — controls colon pulse animation.
 * @param isScrollable     Whether drag-to-scroll is active (typically when timer is idle).
 * @param digitHeight      Height of each FlipDigit card; width is derived at 0.7× height.
 * @param glowColor        Amber glow applied to all digit cards and the colon dots.
 * @param onDurationChange Callback fired with new duration in minutes when user scrolls.
 * @param currentDurationMinutes Current total duration, needed to compute delta on drag.
 */
@Composable
fun FlipClockDisplay(
    remainingSeconds: Long,
    isRunning: Boolean,
    isScrollable: Boolean,
    digitHeight: Dp = 108.dp,
    glowColor: Color = OrpheusColors.sleepMoonlight,
    onDurationChange: ((Int) -> Unit)? = null,
    currentDurationMinutes: Int = 0,
    modifier: Modifier = Modifier,
) {
    val totalMinutes = remainingSeconds / 60
    val hours = (totalMinutes / 60).coerceIn(0, 99)
    val minutes = (totalMinutes % 60).coerceIn(0, 59)

    val hourTens = (hours / 10).toInt()
    val hourOnes = (hours % 10).toInt()
    val minuteTens = (minutes / 10).toInt()
    val minuteOnes = (minutes % 10).toInt()

    // Spacing derived from digit height
    val digitGap = digitHeight * 0.05f
    val colonGap = digitHeight * 0.14f
    val colonSize = digitHeight * 0.08f

    // Colon pulse animation — active only when running
    val colonAlpha = if (isRunning) {
        val infiniteTransition = rememberInfiniteTransition(label = "colonPulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "colonAlpha",
        )
        alpha
    } else {
        0.8f
    }

    // Use rememberUpdatedState so pointerInput gestures always read the latest
    // values without restarting (which would cancel in-progress drags).
    val currentDuration by rememberUpdatedState(currentDurationMinutes)
    val durationCallback by rememberUpdatedState(onDurationChange)

    // Drag accumulators for hours and minutes groups
    var hourDragAccum by remember { mutableFloatStateOf(0f) }
    var minuteDragAccum by remember { mutableFloatStateOf(0f) }

    val dragThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        (digitHeight * 0.5f).toPx()
    }

    fun handleDrag(accum: Float, stepMinutes: Int, delta: Float): Float {
        val newAccum = accum + delta
        val steps = (newAccum / dragThresholdPx).toInt()
        if (steps != 0 && durationCallback != null) {
            // Positive delta = drag down = decrease; negative delta = drag up = increase
            val minutesDelta = -steps * stepMinutes
            val newDuration = (currentDuration + minutesDelta).coerceIn(0, 260)
            durationCallback?.invoke(newDuration)
            return newAccum - steps * dragThresholdPx
        }
        return newAccum
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Hours digit pair
        val hoursPairModifier = if (isScrollable && durationCallback != null) {
            Modifier.pointerInput(isScrollable) {
                detectVerticalDragGestures(
                    onDragEnd = { hourDragAccum = 0f },
                    onDragCancel = { hourDragAccum = 0f },
                ) { _, dragAmount ->
                    hourDragAccum = handleDrag(hourDragAccum, stepMinutes = 60, delta = dragAmount)
                }
            }
        } else {
            Modifier
        }

        Row(
            modifier = hoursPairModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlipDigit(digit = hourTens, height = digitHeight, glowColor = glowColor, animate = isRunning)
            Spacer(modifier = Modifier.width(digitGap))
            FlipDigit(digit = hourOnes, height = digitHeight, glowColor = glowColor, animate = isRunning)
        }

        // Colon separator
        Spacer(modifier = Modifier.width(colonGap))
        ColonDots(
            height = digitHeight,
            colonSize = colonSize,
            glowColor = glowColor.copy(alpha = colonAlpha),
        )
        Spacer(modifier = Modifier.width(colonGap))

        // Minutes digit pair
        val minutesPairModifier = if (isScrollable && durationCallback != null) {
            Modifier.pointerInput(isScrollable) {
                detectVerticalDragGestures(
                    onDragEnd = { minuteDragAccum = 0f },
                    onDragCancel = { minuteDragAccum = 0f },
                ) { _, dragAmount ->
                    minuteDragAccum = handleDrag(minuteDragAccum, stepMinutes = 1, delta = dragAmount)
                }
            }
        } else {
            Modifier
        }

        Row(
            modifier = minutesPairModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlipDigit(digit = minuteTens, height = digitHeight, glowColor = glowColor, animate = isRunning)
            Spacer(modifier = Modifier.width(digitGap))
            FlipDigit(digit = minuteOnes, height = digitHeight, glowColor = glowColor, animate = isRunning)
        }
    }
}

/**
 * Two vertically-stacked round dots that form the colon separator between hours and minutes.
 * Each dot is rendered with a larger glow circle behind it.
 */
@Composable
private fun ColonDots(
    height: Dp,
    colonSize: Dp,
    glowColor: Color,
    modifier: Modifier = Modifier,
) {
    val dotRadius = colonSize / 2f
    val glowRadius = colonSize          // glow circle is 2× the dot radius

    Canvas(
        modifier = modifier
            .width(colonSize * 2f)
            .height(height),
    ) {
        val centerX = size.width / 2f
        val topDotY = size.height * 0.33f
        val bottomDotY = size.height * 0.67f

        val dotR = dotRadius.toPx()
        val glowR = glowRadius.toPx()

        // Top dot
        drawCircle(
            color = glowColor.copy(alpha = glowColor.alpha * 0.35f),
            radius = glowR,
            center = Offset(centerX, topDotY),
        )
        drawCircle(
            color = glowColor,
            radius = dotR,
            center = Offset(centerX, topDotY),
        )

        // Bottom dot
        drawCircle(
            color = glowColor.copy(alpha = glowColor.alpha * 0.35f),
            radius = glowR,
            center = Offset(centerX, bottomDotY),
        )
        drawCircle(
            color = glowColor,
            radius = dotR,
            center = Offset(centerX, bottomDotY),
        )
    }
}
