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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Flip clock display using [FlipDigit]s. Normally renders HH:MM, but when
 * [remainingTime] is below 60 it switches to a single 2-digit SS countdown
 * (no colon) so the final minute reads as a clear seconds-only timer.
 *
 * pairs respond to vertical drag gestures: drag up to increase, drag down to decrease.
 * Hours change by 60-minute steps; minutes change by 1-minute steps. Clamped to 0–260 minutes.
 * (Scroll has no effect in the SS mode — users don't edit the final-60s display.)
 *
 * @param remainingTime Countdown value to display (converted to HH:MM or SS internally).
 * @param isRunning        Whether the timer is running — controls colon pulse animation.
 * @param digitHeight      Height of each FlipDigit card; width is derived at 0.7× height.
 * @param glowColor        Amber glow applied to all digit cards and the colon dots.
 * @param onDurationChange Callback fired with new duration in minutes when user scrolls.
 * @param initialTime Current total duration, needed to compute delta on drag.
 */
@Composable
fun FlipClockDisplay(
    remainingTime: Duration,
    isRunning: Boolean,
    digitHeight: Dp = 108.dp,
    glowColor: Color = OrpheusColors.sleepMoonlight,
    onDurationChange: ((Duration) -> Unit)? = null,
    initialTime: Duration = 0.seconds,
    modifier: Modifier = Modifier,
) {
    val remainingSeconds = remainingTime.inWholeSeconds
    // Final-minute mode: two big digits counting down 59 → 00, no colon, no scroll.
    if (remainingSeconds in 0 until 60) {
        SecondsCountdown(
            seconds = remainingSeconds.toInt(),
            digitHeight = digitHeight,
            glowColor = glowColor,
            animate = isRunning,
            modifier = modifier,
        )
        return
    }

    val totalMinutes = remainingTime.inWholeMinutes
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
    val currentDuration by rememberUpdatedState(initialTime)
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
            val newDuration = (currentDuration + minutesDelta.minutes).clampToTimerLimits()
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
        val hoursPairModifier = if (!isRunning && durationCallback != null) {
            Modifier.pointerInput(!isRunning) {
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
        val minutesPairModifier = if (!isRunning && durationCallback != null) {
            Modifier.pointerInput(!isRunning) {
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
 * Final-minute countdown: two big flip digits showing seconds (00–59). No colon,
 * no scroll handles — this is pure display for the last 60s of the timer.
 *
 * Digit height is scaled up by [kSecondsScaleFactor] from [digitHeight] so the
 * two digits dominate the space the way the 4-digit HH:MM layout did. The
 * outer width is pinned to the 4-digit layout width so switching modes doesn't
 * reflow the surrounding panel.
 */
@Composable
private fun SecondsCountdown(
    seconds: Int,
    digitHeight: Dp,
    glowColor: Color,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val clamped = seconds.coerceIn(0, 59)
    val tens = clamped / 10
    val ones = clamped % 10

    // Scale up the seconds digits for "final countdown" prominence.
    // The pair is centered in the 3.34h HH:MM envelope (see Row width below).
    val bigDigitHeight = digitHeight * kSecondsScaleFactor
    val digitGap = bigDigitHeight * 0.05f

    Row(
        // Match the 4-digit HH:MM layout width so mode-switching is layout-stable.
        //   4 × (h × 0.7) + 2 × (h × 0.05) + 2 × (h × 0.14) + 2 × (h × 0.08) ≈ h × 3.34
        modifier = modifier.width(digitHeight * 3.34f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        FlipDigit(digit = tens, height = bigDigitHeight, glowColor = glowColor, animate = animate)
        Spacer(modifier = Modifier.width(digitGap))
        FlipDigit(digit = ones, height = bigDigitHeight, glowColor = glowColor, animate = animate)
    }
}

private const val kSecondsScaleFactor = 1.2f

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

// ── Previews ────────────────────────────────────────────────────────

@Preview(name = "FlipClock — 1:23 Running", widthDp = 320, heightDp = 140)
@Composable
private fun FlipClockPreviewRunning() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        FlipClockDisplay(
            remainingTime = 1.hours.plus(23.minutes).plus(17.seconds),
            isRunning = true,
            digitHeight = 72.dp,
        )
    }
}

@Preview(name = "FlipClock — 45s Final Minute", widthDp = 320, heightDp = 140)
@Composable
private fun FlipClockPreviewFinalMinute() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        FlipClockDisplay(
            remainingTime = 45.seconds,
            isRunning = true,
            digitHeight = 72.dp,
            glowColor = OrpheusColors.sleepEmber,
        )
    }
}

@Preview(name = "FlipClock — Idle 0:30", widthDp = 320, heightDp = 140)
@Composable
private fun FlipClockPreviewIdle() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        FlipClockDisplay(
            remainingTime = 30.minutes,
            isRunning = false,
            digitHeight = 72.dp,
            initialTime = 30.minutes,
        )
    }
}

@Preview(name = "FlipClock — Idle 0:00", widthDp = 320, heightDp = 140)
@Composable
private fun FlipClockPreviewIdleTime0() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        FlipClockDisplay(
            remainingTime = 0.minutes,
            isRunning = false,
            digitHeight = 72.dp,
            initialTime = 0.minutes,
        )
    }
}

@Preview(name = "FlipClock — Running 0:00", widthDp = 320, heightDp = 140)
@Composable
private fun FlipClockPreviewRunningTime0() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        FlipClockDisplay(
            remainingTime = 0.minutes,
            isRunning = true,
            digitHeight = 72.dp,
            initialTime = 0.minutes,
        )
    }
}
