@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.balch.orpheus.core.media

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS implementation of MediaSessionManager.
 *
 * Stub for now — full MPNowPlayingInfoCenter / MPRemoteCommandCenter
 * integration will be added later.
 */
@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager {
    private var handler: MediaSessionActionHandler? = null

    actual fun activate() {
        // TODO: Configure MPNowPlayingInfoCenter + MPRemoteCommandCenter
    }

    actual fun deactivate() {
        // TODO: Tear down now-playing session
    }

    actual fun updatePlaybackState(isPlaying: Boolean) {
        // TODO: Update MPNowPlayingInfoCenter playback rate
    }

    actual fun updateMetadata(metadata: PlaybackMetadata) {
        // TODO: Update MPNowPlayingInfoCenter nowPlayingInfo dict
    }

    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
}
