package org.balch.djapp

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.balch.orpheus.core.media.ForegroundServiceController

/**
 * Android implementation of ForegroundServiceController for the DJ app.
 *
 * Drives the DjMediaBrowserService (which owns the MediaSession and
 * foreground notification).
 */
class DjForegroundServiceControllerImpl(
    private val context: Context
) : ForegroundServiceController {

    override var actionHandler: ((String) -> Unit)?
        get() = DjMediaBrowserService.actionHandler
        set(value) {
            DjMediaBrowserService.actionHandler = value
        }

    override var playFromMediaIdHandler: ((String) -> Unit)?
        get() = DjMediaBrowserService.playFromMediaIdHandler
        set(value) {
            DjMediaBrowserService.playFromMediaIdHandler = value
        }

    override fun start() {
        val intent = Intent(context, DjMediaBrowserService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        context.stopService(Intent(context, DjMediaBrowserService::class.java))
    }

    override fun updatePlaybackState(isPlaying: Boolean) {
        val intent = Intent(context, DjMediaBrowserService::class.java).apply {
            action = if (isPlaying) {
                ForegroundServiceController.ACTION_UPDATE_STATE_PLAYING
            } else {
                ForegroundServiceController.ACTION_UPDATE_STATE_PAUSED
            }
        }
        context.startService(intent)
    }

    override fun updateMetadata(title: String, subtitle: String, isPlaying: Boolean) {
        val intent = Intent(context, DjMediaBrowserService::class.java).apply {
            action = ForegroundServiceController.ACTION_UPDATE_METADATA
            putExtra(ForegroundServiceController.EXTRA_TITLE, title)
            putExtra(ForegroundServiceController.EXTRA_SUBTITLE, subtitle)
            putExtra(ForegroundServiceController.EXTRA_IS_PLAYING, isPlaying)
        }
        context.startService(intent)
    }

}
