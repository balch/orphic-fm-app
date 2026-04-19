package org.balch.orpheus.features.timer

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.features.timer.TimerLimits.MAX_HOURS
import org.balch.orpheus.features.timer.TimerLimits.MAX_MINUTES_AT_CAP_HOUR
import org.balch.orpheus.features.timer.TimerLimits.MAX_MINUTES_NORMAL
import org.balch.orpheus.features.timer.TimerLimits.MIN_HOURS
import org.balch.orpheus.features.timer.TimerLimits.MaxDuration
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Single source of truth for the timer's duration range and knob rails.
 *
 * The HR knob steps from [MIN_HOURS] to [MAX_HOURS]; the M knob steps from 0 to
 * [MAX_MINUTES_NORMAL], clamping to [MAX_MINUTES_AT_CAP_HOUR] when HR == [MAX_HOURS].
 * [MaxDuration] is derived from those bounds so every clamp in the feature agrees
 * with the knob range exactly — no more `260.seconds` unit mishaps.
 */
internal object TimerLimits {
    const val MIN_HOURS: Long = 0L
    const val MAX_HOURS: Long = 4L
    const val MAX_MINUTES_AT_CAP_HOUR: Long = 20L
    const val MAX_MINUTES_NORMAL: Long = 59L

    val MinDuration: Duration = 1.minutes
    val MaxDuration: Duration = MAX_HOURS.hours + MAX_MINUTES_AT_CAP_HOUR.minutes
    val DefaultDuration: Duration = 42.minutes
}

/**
 * Clamps this duration to the timer's allowed range ([TimerLimits.MinDuration]
 * … [TimerLimits.MaxDuration]). Used anywhere a user-supplied duration flows
 * into state (setDuration, scroll-drag, etc.) so units and bounds agree.
 */
internal fun Duration.clampToTimerLimits(): Duration =
    coerceIn(TimerLimits.MinDuration, TimerLimits.MaxDuration)

internal fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

internal fun formatTime(duration: Duration): String =
    duration.toComponents { h, m, s, _ ->
        if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    }

internal fun resolveGlowColor(status: TimerStatus, remaining: Duration): Color {
    val remainingSeconds = remaining.inWholeSeconds
    return when {
        status == TimerStatus.FADING -> OrpheusColors.sleepEmber
        remainingSeconds in 1..60 && status == TimerStatus.RUNNING -> {
            val t = 1f - (remainingSeconds / 60f)
            lerpColor(OrpheusColors.sleepMoonlight, OrpheusColors.sleepEmber, t)
        }
        else -> OrpheusColors.sleepMoonlight
    }
}
