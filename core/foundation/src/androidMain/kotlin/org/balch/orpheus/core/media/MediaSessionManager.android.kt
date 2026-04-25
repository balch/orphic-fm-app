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
    actual var onSkipNext: (() -> Unit)? = null
    actual var onSkipPrevious: (() -> Unit)? = null
    actual var onPlay: (() -> Unit)? = null
    actual var onPause: (() -> Unit)? = null
    actual var onPlayFromMediaId: ((String) -> Unit)? = null

    actual fun activate() {
        if (isActive) return

        log.info { "Activating media session" }

        // Set up the action handler bridge before starting the service
        foregroundServiceController.actionHandler = { action ->
            log.debug { "Received action from service: $action" }
            when (action) {
                "play" -> {
                    val customHandler = onPlay
                    if (customHandler != null) customHandler() else handler?.onPlay()
                }
                "pause" -> {
                    val customHandler = onPause
                    if (customHandler != null) customHandler() else handler?.onPause()
                }
                "stop" -> handler?.onStop()
                "skipNext" -> onSkipNext?.invoke() ?: handler?.onSkipNext()
                "skipPrevious" -> onSkipPrevious?.invoke() ?: handler?.onSkipPrevious()
            }
        }
        foregroundServiceController.playFromMediaIdHandler = { mediaId ->
            log.debug { "Received playFromMediaId: $mediaId" }
            onPlayFromMediaId?.invoke(mediaId)
        }

        foregroundServiceController.start()
        isActive = true
    }

    actual fun deactivate() {
        if (!isActive) return

        log.info { "Deactivating media session" }
        // Clear handler BEFORE stopping service to prevent reentrant
        // callbacks during teardown (e.g. MediaSession.Callback.onStop
        // firing during service destruction)
        foregroundServiceController.actionHandler = null
        foregroundServiceController.playFromMediaIdHandler = null
        foregroundServiceController.stop()
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
            title = metadata.title,
            mode = metadata.mode.name,
            modeDisplayName = metadata.displaySubtitle,
            isPlaying = metadata.isPlaying
        )
    }
}
