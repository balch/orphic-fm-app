package org.balch.orpheus.core.media

/**
 * Cross-platform media session manager for system media controls integration.
 * 
 * Handles:
 * - Background audio playback (Android foreground service)
 * - Lock screen / notification media controls
 * - Hardware media key events
 */
expect class MediaSessionManager {
    /**
     * Activate the media session and start background audio support.
     * On Android, this starts a foreground service with a notification.
     */
    fun activate()
    
    /**
     * Deactivate the media session and stop background audio support.
     */
    fun deactivate()
    
    /**
     * Update the current playback state for system UI.
     */
    fun updatePlaybackState(isPlaying: Boolean)
    
    /**
     * Set the handler for media button actions (play/pause/stop).
     */
    fun setActionHandler(handler: MediaSessionActionHandler)
}

/**
 * Callback interface for media session actions from system controls.
 */
interface MediaSessionActionHandler {
    fun onPlay()
    fun onPause()
    fun onStop()
}
