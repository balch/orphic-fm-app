package org.balch.orpheus.core.media

/**
 * Interface for controlling the audio foreground service.
 * 
 * This abstraction allows the media session manager in the shared library
 * to control the foreground service without depending on the concrete
 * implementation in the app module.
 */
interface ForegroundServiceController {
    
    /**
     * Action handler callback for media button actions.
     */
    var actionHandler: ((String) -> Unit)?
    
    /**
     * Start the foreground service.
     */
    fun start()
    
    /**
     * Stop the foreground service.
     */
    fun stop()
    
    /**
     * Update the playback state (playing/paused).
     */
    fun updatePlaybackState(isPlaying: Boolean)
    
    /**
     * Update the metadata (mode, display name, etc.).
     */
    fun updateMetadata(mode: String, modeDisplayName: String, isPlaying: Boolean)
    
    companion object {
        // Action constants that match AudioForegroundService
        const val ACTION_PLAY = "org.balch.orpheus.PLAY"
        const val ACTION_PAUSE = "org.balch.orpheus.PAUSE"
        const val ACTION_STOP = "org.balch.orpheus.STOP"
        const val ACTION_UPDATE_STATE_PLAYING = "org.balch.orpheus.UPDATE_STATE_PLAYING"
        const val ACTION_UPDATE_STATE_PAUSED = "org.balch.orpheus.UPDATE_STATE_PAUSED"
        const val ACTION_UPDATE_METADATA = "org.balch.orpheus.UPDATE_METADATA"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_MODE_DISPLAY_NAME = "extra_mode_display_name"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
    }
}
