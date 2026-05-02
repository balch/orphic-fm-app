package org.balch.orpheus.core.media

import com.diamondedge.logging.logging

/**
 * JNI bridge to macOS MPNowPlayingInfoCenter and MPRemoteCommandCenter.
 *
 * Native code lives in liborpheus_dsp/desktop/macos_now_playing.mm,
 * compiled into liborpheus_desktop.dylib which is already loaded by DesktopDspBridge.
 */
object MacOsNowPlaying {
    private val log = logging("MacOsNowPlaying")

    val isAvailable: Boolean = System.getProperty("os.name", "").lowercase().let {
        "mac" in it || "darwin" in it
    }

    interface Callback {
        fun onPlay()
        fun onPause()
        fun onTogglePlayPause()
        fun onNext()
        fun onPrevious()
    }

    fun setup(callback: Callback) {
        if (!isAvailable) return
        try {
            nativeSetup(callback)
            log.info { "macOS Now Playing activated" }
        } catch (e: UnsatisfiedLinkError) {
            log.warn { "macOS Now Playing native not available: ${e.message}" }
        }
    }

    fun updateMetadata(title: String, artist: String) {
        if (!isAvailable) return
        try {
            nativeUpdateMetadata(title, artist)
        } catch (_: UnsatisfiedLinkError) {
            // Silently ignore if native not loaded
        }
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        if (!isAvailable) return
        try {
            nativeUpdatePlaybackState(isPlaying)
        } catch (_: UnsatisfiedLinkError) {
            // Silently ignore
        }
    }

    /**
     * Push artwork to MPNowPlayingInfoCenter. Pass null to clear.
     * The bytes should encode a PNG/JPEG that NSImage can decode.
     */
    fun updateArtwork(pngBytes: ByteArray?) {
        if (!isAvailable) return
        try {
            nativeUpdateArtwork(pngBytes)
        } catch (_: UnsatisfiedLinkError) {
            // Silently ignore if native not loaded
        }
    }

    fun teardown() {
        if (!isAvailable) return
        try {
            nativeTeardown()
            log.info { "macOS Now Playing deactivated" }
        } catch (_: UnsatisfiedLinkError) {
            // Silently ignore
        }
    }

    @JvmStatic private external fun nativeSetup(callback: Callback)
    @JvmStatic private external fun nativeUpdateMetadata(title: String, artist: String)
    @JvmStatic private external fun nativeUpdatePlaybackState(isPlaying: Boolean)
    @JvmStatic private external fun nativeUpdateArtwork(pngBytes: ByteArray?)
    @JvmStatic private external fun nativeTeardown()
}
