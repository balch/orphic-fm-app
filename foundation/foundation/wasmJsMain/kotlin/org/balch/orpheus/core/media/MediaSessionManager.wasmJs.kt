@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

// Top-level JS interop functions for MediaSession API
// In Kotlin/Wasm, js() calls must be at top-level function bodies or property initializers
// and need explicit return types

private fun jsSetPlaybackState(isPlaying: Boolean): Unit = js(
    "{ if ('mediaSession' in navigator) { navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused'; } }"
)

private fun jsUpdateMetadata(title: String, artist: String, album: String): Unit = js(
    "{ if ('mediaSession' in navigator) { navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: artist, album: album }); } }"
)

private fun jsSetupInitialMetadata(): Unit = js(
    "{ if ('mediaSession' in navigator) { navigator.mediaSession.metadata = new MediaMetadata({ title: 'Orpheus Synthesizer', artist: 'Playing', album: 'Live Session' }); } }"
)

private fun jsClearMediaSession(): Unit = js("""{
    if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = null;
        navigator.mediaSession.setActionHandler('play', null);
        navigator.mediaSession.setActionHandler('pause', null);
        navigator.mediaSession.setActionHandler('stop', null);
    }
}""")

private fun jsHasMediaSession(): Boolean = js("('mediaSession' in navigator)")

/**
 * Web (Wasm/JS) implementation of MediaSessionManager.
 * 
 * Uses the navigator.mediaSession Web API for browser media controls.
 */
@SingleIn(AppScope::class)
@Inject
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
            jsClearMediaSession()
        } catch (e: Exception) {
            log.debug { "Error clearing MediaSession: ${e.message}" }
        }
        isActive = false
        log.info { "MediaSessionManager deactivated" }
    }
    
    actual fun updatePlaybackState(isPlaying: Boolean) {
        try {
            jsSetPlaybackState(isPlaying)
        } catch (e: Exception) {
            // MediaSession may not be available
        }
    }
    
    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
    
    actual fun updateMetadata(metadata: PlaybackMetadata) {
        try {
            jsUpdateMetadata(metadata.title, metadata.subtitle, metadata.mode.displayName)
        } catch (e: Exception) {
            // MediaSession may not be available
        }
    }
    
    private fun setupMediaSession() {
        // Set initial metadata
        jsSetupInitialMetadata()
        
        // Note: Setting action handlers with callbacks in Kotlin/Wasm is complex
        // and would require JsExport of callback functions. For now, we only
        // support metadata updates. Full action handler support would need
        // additional Wasm-JS interop setup.
        
        // The handlers are stored but not wired up via JS interop
        // A future enhancement could use @JsExport to expose callbacks
    }
}
