package org.balch.orpheus.features.timer

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.ui.theme.OrpheusColors

internal fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

internal fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m}:${s.toString().padStart(2, '0')}"
}

internal fun resolveGlowColor(status: TimerStatus, remainingSeconds: Long): Color {
    return when {
        status == TimerStatus.FADING -> OrpheusColors.sleepEmber
        remainingSeconds in 1..60 && status == TimerStatus.RUNNING -> {
            val t = 1f - (remainingSeconds / 60f)
            lerpColor(OrpheusColors.sleepMoonlight, OrpheusColors.sleepEmber, t)
        }
        else -> OrpheusColors.sleepMoonlight
    }
}
