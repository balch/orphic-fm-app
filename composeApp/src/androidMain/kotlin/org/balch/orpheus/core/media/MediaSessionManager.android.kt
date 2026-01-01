package org.balch.orpheus.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
    private var handler: MediaSessionActionHandler? = null
    private var isActive = false
    
    actual fun activate() {
        if (isActive) return
        
        // Set up the action handler bridge before starting the service
        AudioForegroundService.actionHandler = { action ->
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
        
        context.stopService(Intent(context, AudioForegroundService::class.java))
        AudioForegroundService.actionHandler = null
        isActive = false
    }
    
    actual fun updatePlaybackState(isPlaying: Boolean) {
        // The service handles its own state updates through MediaSession
        // This could be enhanced to send an intent to update state
    }
    
    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
}
