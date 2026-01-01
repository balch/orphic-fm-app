@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.balch.orpheus.core.media

import com.diamondedge.logging.logging

/**
 * Web (Wasm/JS) implementation of MediaSessionManager.
 * 
 * Uses the navigator.mediaSession Web API for browser media controls.
 */
actual class MediaSessionManager {
    private val log = logging("MediaSessionManager")
    private var handler: MediaSessionActionHandler? = null
    private var isActive = false
    
    actual fun activate() {
        if (isActive) return
        
        try {
            setupMediaSession()
            isActive = true
            log.info { "MediaSessionManager activated" }
        } catch (e: Exception) {
            log.debug { "MediaSession API not available: ${e.message}" }
            isActive = true
        }
    }
    
    actual fun deactivate() {
        if (!isActive) return
        
        try {
            clearMediaSession()
        } catch (e: Exception) {
            log.debug { "Error clearing MediaSession: ${e.message}" }
        }
        isActive = false
        log.info { "MediaSessionManager deactivated" }
    }
    
    actual fun updatePlaybackState(isPlaying: Boolean) {
        try {
            js("navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused'")
        } catch (e: Exception) {
            // MediaSession may not be available
        }
    }
    
    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
    
    actual fun updateMetadata(metadata: PlaybackMetadata) {
        try {
            updateMediaSessionMetadata(metadata.title, metadata.subtitle, metadata.mode.displayName)
        } catch (e: Exception) {
            // MediaSession may not be available
        }
    }
    
    private fun updateMediaSessionMetadata(title: String, artist: String, album: String) {
        js("""
            if ('mediaSession' in navigator) {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: title,
                    artist: artist,
                    album: album
                });
            }
        """)
    }
    
    private fun setupMediaSession() {
        // Set metadata
        js("""
            if ('mediaSession' in navigator) {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: 'Orpheus Synthesizer',
                    artist: 'Playing',
                    album: 'Live Session'
                });
            }
        """)
        
        // Set action handlers
        setJsActionHandler("play") { handler?.onPlay() }
        setJsActionHandler("pause") { handler?.onPause() }
        setJsActionHandler("stop") { handler?.onStop() }
    }
    
    private fun clearMediaSession() {
        js("""
            if ('mediaSession' in navigator) {
                navigator.mediaSession.metadata = null;
                navigator.mediaSession.setActionHandler('play', null);
                navigator.mediaSession.setActionHandler('pause', null);
                navigator.mediaSession.setActionHandler('stop', null);
            }
        """)
    }
    
    private fun setJsActionHandler(action: String, callback: () -> Unit) {
        // Use dynamic JS interop to set action handlers
        js("""
            if ('mediaSession' in navigator) {
                navigator.mediaSession.setActionHandler(action, function() {
                    callback();
                });
            }
        """)
    }
}
