package org.balch.orpheus.features.timer.widget

import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.features.timer.TimerWidgetNotifier

/**
 * Sends a broadcast to [TimerWidgetProvider] on each timer state transition,
 * so the home screen widget updates its Chronometer display.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class, binding = binding<TimerWidgetNotifier>())
class AndroidTimerWidgetNotifier(
    private val context: Context,
) : TimerWidgetNotifier {

    override fun notifyStateChanged(remainingSeconds: Long, running: Boolean, statusText: String) {
        val intent = Intent(context, TimerWidgetProvider::class.java).apply {
            action = TimerWidgetProvider.ACTION_STATE_UPDATE
            putExtra(TimerWidgetProvider.EXTRA_REMAINING_SECONDS, remainingSeconds)
            putExtra(TimerWidgetProvider.EXTRA_RUNNING, running)
            putExtra(TimerWidgetProvider.EXTRA_STATUS, statusText)
        }
        context.sendBroadcast(intent)
    }
}
