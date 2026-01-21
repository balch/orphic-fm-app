package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android implementation of MediaSessionManager.
 * 
 * Starts/stops the foreground service and bridges media actions
 * to the registered handler.
 */
@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager(
    private val foregroundServiceController: ForegroundServiceController
) {
    private val log = logging("MediaSessionManager")
    private var handler: MediaSessionActionHandler? = null
    private var isActive = false
    
    actual fun activate() {
        if (isActive) return
        
        log.info { "Activating media session" }
        
        // Set up the action handler bridge before starting the service
        foregroundServiceController.actionHandler = { action ->
            log.debug { "Received action from service: $action" }
            when (action) {
                "play" -> handler?.onPlay()
                "pause" -> handler?.onPause()
                "stop" -> handler?.onStop()
            }
        }
        
        foregroundServiceController.start()
        isActive = true
    }
    
    actual fun deactivate() {
        if (!isActive) return
        
        log.info { "Deactivating media session" }
        foregroundServiceController.stop()
        foregroundServiceController.actionHandler = null
        isActive = false
    }
    
    actual fun updatePlaybackState(isPlaying: Boolean) {
        if (!isActive) return
        
        log.debug { "Updating playback state: isPlaying=$isPlaying" }
        foregroundServiceController.updatePlaybackState(isPlaying)
    }
    
    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
    
    actual fun updateMetadata(metadata: PlaybackMetadata) {
        if (!isActive) return
        
        log.debug { "Updating metadata: mode=${metadata.mode}, isPlaying=${metadata.isPlaying}" }
        foregroundServiceController.updateMetadata(
            mode = metadata.mode.name,
            modeDisplayName = metadata.mode.displayName,
            isPlaying = metadata.isPlaying
        )
    }
}
