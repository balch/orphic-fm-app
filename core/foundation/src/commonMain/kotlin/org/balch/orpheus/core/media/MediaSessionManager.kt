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
     * Update the playback metadata (mode, title) for system UI.
     * This updates what is displayed in notifications and lock screen.
     */
    fun updateMetadata(metadata: PlaybackMetadata)

    /**
     * Set the handler for media button actions (play/pause/stop).
     */
    fun setActionHandler(handler: MediaSessionActionHandler)

    /**
     * Synchronously try to acquire audio focus for playback. Returns true on
     * platforms without a focus model. Must be called from PlaybackController.play()
     * BEFORE unmuting so that a denied focus request rolls back cleanly instead
     * of bleeding audio without focus or a foreground service.
     */
    fun requestPlaybackFocus(): Boolean

    /**
     * Signal that the user explicitly paused/stopped. On Android, clears the
     * "auto-resume on focus gain" flag so that a later AUDIOFOCUS_GAIN does not
     * resume playback against the user's intent. No-op on other platforms.
     */
    fun notifyUserPaused()
}

/**
 * Callback interface for media session actions from system controls.
 */
interface MediaSessionActionHandler {
    fun onPlay()
    fun onPause()
    fun onStop()
    fun onSkipNext() {}
    fun onSkipPrevious() {}
    fun onPlayFromMediaId(mediaId: String) {}
    /**
     * Called when the platform paused us due to a transient audio-focus loss
     * (e.g., incoming phone call). Implementations should pause WITHOUT
     * signalling user intent back to the focus controller — pausedByTransient
     * must stay set so the next AUDIOFOCUS_GAIN auto-resumes.
     *
     * Returns true iff this call actually interrupted LIVE playback (i.e. we
     * were Playing). The focus controller arms auto-resume only on a true
     * return: a transient loss that arrives while we are already paused (the
     * user paused but we still hold focus) must NOT arm a resume, or the
     * matching AUDIOFOCUS_GAIN would play against the user's intent.
     */
    fun onPauseFromFocusLoss(): Boolean = false
}
