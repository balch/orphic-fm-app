package org.balch.orpheus.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.AudioForegroundService

/**
 * Android implementation of MediaSessionManager.
 * 
 * Starts/stops the foreground service and bridges media actions
 * to the registered handler.
 */
@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager(
    private val context: Context
) {
    private val log = logging("MediaSessionManager")
    private var handler: MediaSessionActionHandler? = null
    private var isActive = false
    
    actual fun activate() {
        if (isActive) return
        
        log.info { "Activating media session" }
        
        // Set up the action handler bridge before starting the service
        AudioForegroundService.actionHandler = { action ->
            log.debug { "Received action from service: $action" }
            when (action) {
                "play" -> handler?.onPlay()
                "pause" -> handler?.onPause()
                "stop" -> handler?.onStop()
            }
        }
        
        val intent = Intent(context, AudioForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
        isActive = true
    }
    
    actual fun deactivate() {
        if (!isActive) return
        
        log.info { "Deactivating media session" }
        context.stopService(Intent(context, AudioForegroundService::class.java))
        AudioForegroundService.actionHandler = null
        isActive = false
    }
    
    actual fun updatePlaybackState(isPlaying: Boolean) {
        if (!isActive) return
        
        log.debug { "Updating playback state: isPlaying=$isPlaying" }
        
        // Send intent to update the service's notification state
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            action = if (isPlaying) {
                AudioForegroundService.ACTION_UPDATE_STATE_PLAYING
            } else {
                AudioForegroundService.ACTION_UPDATE_STATE_PAUSED
            }
        }
        context.startService(intent)
    }
    
    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
    
    actual fun updateMetadata(metadata: PlaybackMetadata) {
        if (!isActive) return
        
        log.debug { "Updating metadata: mode=${metadata.mode}, isPlaying=${metadata.isPlaying}" }
        
        // Send intent to update the service's metadata
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_UPDATE_METADATA
            putExtra(AudioForegroundService.EXTRA_MODE, metadata.mode.name)
            putExtra(AudioForegroundService.EXTRA_MODE_DISPLAY_NAME, metadata.mode.displayName)
            putExtra(AudioForegroundService.EXTRA_IS_PLAYING, metadata.isPlaying)
        }
        context.startService(intent)
    }
}

