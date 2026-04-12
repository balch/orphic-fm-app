package org.balch.djapp

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.balch.orpheus.core.media.ForegroundServiceController

/**
 * Android implementation of ForegroundServiceController for the DJ app.
 *
 * Controls the DjAudioForegroundService lifecycle and delegates
 * action handler callbacks.
 *
 * This is manually instantiated in DjAppApplication and passed
 * to the DI graph factory.
 */
class DjForegroundServiceControllerImpl(
    private val context: Context
) : ForegroundServiceController {

    override var actionHandler: ((String) -> Unit)?
        get() = DjAudioForegroundService.actionHandler
        set(value) {
            DjAudioForegroundService.actionHandler = value
        }

    override fun start() {
        val intent = Intent(context, DjAudioForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        context.stopService(Intent(context, DjAudioForegroundService::class.java))
    }

    override fun updatePlaybackState(isPlaying: Boolean) {
        val intent = Intent(context, DjAudioForegroundService::class.java).apply {
            action = if (isPlaying) {
                ForegroundServiceController.ACTION_UPDATE_STATE_PLAYING
            } else {
                ForegroundServiceController.ACTION_UPDATE_STATE_PAUSED
            }
        }
        context.startService(intent)
    }

    override fun updateMetadata(title: String, mode: String, modeDisplayName: String, isPlaying: Boolean) {
        val intent = Intent(context, DjAudioForegroundService::class.java).apply {
            action = ForegroundServiceController.ACTION_UPDATE_METADATA
            putExtra(ForegroundServiceController.EXTRA_TITLE, title)
            putExtra(ForegroundServiceController.EXTRA_SUBTITLE, modeDisplayName)
            putExtra(ForegroundServiceController.EXTRA_IS_PLAYING, isPlaying)
        }
        context.startService(intent)
    }
}
