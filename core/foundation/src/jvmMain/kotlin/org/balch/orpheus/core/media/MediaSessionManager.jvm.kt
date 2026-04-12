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
    actual var onSkipNext: (() -> Unit)? = null
    actual var onSkipPrevious: (() -> Unit)? = null
    actual var onPlay: (() -> Unit)? = null
    actual var onPause: (() -> Unit)? = null

    actual fun activate() {
        if (isActive) return
        log.info { "Activating media session" }

        MacOsNowPlaying.setup(object : MacOsNowPlaying.Callback {
            override fun onPlay() {
                val customHandler = onPlay
                if (customHandler != null) customHandler() else handler?.onPlay()
            }
            override fun onPause() {
                val customHandler = onPause
                if (customHandler != null) customHandler() else handler?.onPause()
            }
            override fun onTogglePlayPause() {
                if (isPlaying) handler?.onPause() else handler?.onPlay()
            }
            override fun onNext() {
                onSkipNext?.invoke()
            }
            override fun onPrevious() {
                onSkipPrevious?.invoke()
            }
        })

        isActive = true
    }

    actual fun deactivate() {
        if (!isActive) return
        log.info { "Deactivating media session" }
        MacOsNowPlaying.teardown()
        isActive = false
    }

    actual fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        MacOsNowPlaying.updatePlaybackState(isPlaying)
    }

    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }

    actual fun updateMetadata(metadata: PlaybackMetadata) {
        if (!isActive) return
        MacOsNowPlaying.updateMetadata(metadata.title, metadata.displaySubtitle)
        MacOsNowPlaying.updatePlaybackState(metadata.isPlaying)
        isPlaying = metadata.isPlaying
    }
}
