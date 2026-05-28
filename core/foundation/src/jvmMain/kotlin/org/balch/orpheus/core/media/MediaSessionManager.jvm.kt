package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager {
    private val log = logging("MediaSessionManager")
    private var handler: MediaSessionActionHandler? = null
    private var isActive = false
    private var isPlaying = false

    // Cache the latest metadata so `activate()` can replay it. Without this
    // the metadata combine collector in PlaybackController fires before the
    // session is active (since both launch concurrently), the no-op silently
    // discards it, and the now-playing widget stays blank until the user
    // changes a vibe. Caching means activation = an immediate metadata push.
    private var latestMetadata: PlaybackMetadata? = null

    actual fun activate() {
        if (isActive) return
        log.info { "Activating media session" }

        MacOsNowPlaying.setup(object : MacOsNowPlaying.Callback {
            override fun onPlay() {
                handler?.onPlay()
            }
            override fun onPause() {
                handler?.onPause()
            }
            override fun onTogglePlayPause() {
                if (isPlaying) handler?.onPause() else handler?.onPlay()
            }
            override fun onNext() {
                handler?.onSkipNext()
            }
            override fun onPrevious() {
                handler?.onSkipPrevious()
            }
        })

        isActive = true
        // Replay any metadata that arrived before activation so the widget
        // shows the current title/art immediately, not after the next vibe change.
        latestMetadata?.let { pushToNative(it) }
    }

    actual fun deactivate() {
        if (!isActive) return
        log.info { "Deactivating media session" }
        MacOsNowPlaying.teardown()
        isActive = false
    }

    actual fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        if (!isActive) return
        MacOsNowPlaying.updatePlaybackState(isPlaying)
    }

    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }

    // No audio-focus model on JVM desktop — playback always proceeds. The
    // `focusGrantOverride` and `userPausedCount` fields below are test seams
    // (internal visibility, not exposed beyond core:foundation): production
    // code never reads or writes them.
    internal var focusGrantOverride: Boolean = true
    internal var userPausedCount: Int = 0

    actual fun requestPlaybackFocus(): Boolean = focusGrantOverride

    actual fun notifyUserPaused() {
        userPausedCount++
    }

    actual fun updateMetadata(metadata: PlaybackMetadata) {
        latestMetadata = metadata
        if (!isActive) return
        pushToNative(metadata)
    }

    private fun pushToNative(metadata: PlaybackMetadata) {
        MacOsNowPlaying.updateMetadata(metadata.title, metadata.subtitle)
        MacOsNowPlaying.updateArtwork(metadata.artworkPng)
        MacOsNowPlaying.updatePlaybackState(metadata.isPlaying)
        isPlaying = metadata.isPlaying
    }
}
