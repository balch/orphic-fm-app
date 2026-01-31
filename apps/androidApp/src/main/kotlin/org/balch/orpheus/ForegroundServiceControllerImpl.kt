package org.balch.orpheus

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.balch.orpheus.core.media.ForegroundServiceController

/**
 * Android implementation of ForegroundServiceController.
 * 
 * Controls the AudioForegroundService lifecycle and delegates
 * action handler callbacks.
 * 
 * This is manually instantiated in OrpheusApplication and passed
 * to the DI graph factory.
 */
class ForegroundServiceControllerImpl(
    private val context: Context
) : ForegroundServiceController {
    
    override var actionHandler: ((String) -> Unit)?
        get() = AudioForegroundService.actionHandler
        set(value) {
            AudioForegroundService.actionHandler = value
        }
    
    override fun start() {
        val intent = Intent(context, AudioForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }
    
    override fun stop() {
        context.stopService(Intent(context, AudioForegroundService::class.java))
    }
    
    override fun updatePlaybackState(isPlaying: Boolean) {
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            action = if (isPlaying) {
                ForegroundServiceController.ACTION_UPDATE_STATE_PLAYING
            } else {
                ForegroundServiceController.ACTION_UPDATE_STATE_PAUSED
            }
        }
        context.startService(intent)
    }
    
    override fun updateMetadata(mode: String, modeDisplayName: String, isPlaying: Boolean) {
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            action = ForegroundServiceController.ACTION_UPDATE_METADATA
            putExtra(ForegroundServiceController.EXTRA_MODE, mode)
            putExtra(ForegroundServiceController.EXTRA_MODE_DISPLAY_NAME, modeDisplayName)
            putExtra(ForegroundServiceController.EXTRA_IS_PLAYING, isPlaying)
        }
        context.startService(intent)
    }
}
