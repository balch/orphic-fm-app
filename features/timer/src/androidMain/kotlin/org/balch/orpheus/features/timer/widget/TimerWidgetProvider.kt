package org.balch.orpheus.features.timer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.diamondedge.logging.logging
import org.balch.orpheus.features.timer.R
import org.balch.orpheus.features.timer.TimerWidgetCommand
import org.balch.orpheus.features.timer.TimerWidgetCommandBus

private val log = logging("TimerWidget")

class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Restore last-known state instead of resetting to defaults
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = prefs.getLong(KEY_REMAINING, 30 * 60L)
        val running = prefs.getBoolean(KEY_RUNNING, false)
        val status = prefs.getString(KEY_STATUS, "IDLE") ?: "IDLE"
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id, remaining, running, status)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                log.debug { "Widget: play/pause tapped" }
                TimerWidgetCommandBus.send(TimerWidgetCommand.PLAY_PAUSE)
            }
            ACTION_STOP -> {
                log.debug { "Widget: stop tapped" }
                TimerWidgetCommandBus.send(TimerWidgetCommand.STOP)
            }
            ACTION_STATE_UPDATE -> {
                val remaining = intent.getLongExtra(EXTRA_REMAINING_SECONDS, 0L)
                val running = intent.getBooleanExtra(EXTRA_RUNNING, false)
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "IDLE"

                // Persist state so onUpdate can restore it
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_REMAINING, remaining)
                    .putBoolean(KEY_RUNNING, running)
                    .putString(KEY_STATUS, status)
                    .apply()

                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, TimerWidgetProvider::class.java))
                for (id in ids) {
                    updateWidget(context, mgr, id, remaining, running, status)
                }
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "org.balch.orpheus.timer.PLAY_PAUSE"
        const val ACTION_STOP = "org.balch.orpheus.timer.STOP"
        const val ACTION_STATE_UPDATE = "org.balch.orpheus.timer.STATE_UPDATE"
        const val EXTRA_REMAINING_SECONDS = "remaining_seconds"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_STATUS = "status"

        private const val PREFS_NAME = "timer_widget"
        private const val KEY_REMAINING = "remaining"
        private const val KEY_RUNNING = "running"
        private const val KEY_STATUS = "status"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            remainingSeconds: Long = 30 * 60L,
            running: Boolean = false,
            statusText: String = "IDLE",
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_timer)

            // Configure countdown: base = now + remaining so it counts down to zero
            val base = SystemClock.elapsedRealtime() + remainingSeconds * 1_000L
            views.setChronometer(R.id.timer_chronometer, base, null, running)
            views.setChronometerCountDown(R.id.timer_chronometer, true)

            // Toggle play/pause icon — use text glyphs, not emoji (emoji render with colored boxes)
            views.setTextViewText(R.id.btn_play_pause, if (running) "\u275A\u275A" else "\u25B6")

            val playIntent = Intent(context, TimerWidgetProvider::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
            views.setOnClickPendingIntent(
                R.id.btn_play_pause,
                PendingIntent.getBroadcast(context, 0, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )

            val stopIntent = Intent(context, TimerWidgetProvider::class.java).apply {
                action = ACTION_STOP
            }
            views.setOnClickPendingIntent(
                R.id.btn_stop,
                PendingIntent.getBroadcast(context, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
