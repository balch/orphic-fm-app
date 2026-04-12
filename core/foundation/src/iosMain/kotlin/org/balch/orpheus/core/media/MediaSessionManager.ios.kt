@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalForeignApi::class)

package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

/**
 * iOS implementation of MediaSessionManager.
 *
 * Uses MPNowPlayingInfoCenter for lock screen / Control Center metadata
 * and MPRemoteCommandCenter for transport controls (play, pause, skip).
 */
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

        val cc = MPRemoteCommandCenter.sharedCommandCenter()

        cc.playCommand.addTargetWithHandler { _ ->
            val custom = onPlay
            if (custom != null) custom() else handler?.onPlay()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.pauseCommand.addTargetWithHandler { _ ->
            val custom = onPause
            if (custom != null) custom() else handler?.onPause()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.togglePlayPauseCommand.addTargetWithHandler { _ ->
            if (isPlaying) {
                val custom = onPause
                if (custom != null) custom() else handler?.onPause()
            } else {
                val custom = onPlay
                if (custom != null) custom() else handler?.onPlay()
            }
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.stopCommand.addTargetWithHandler { _ ->
            handler?.onStop()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.nextTrackCommand.addTargetWithHandler { _ ->
            onSkipNext?.invoke()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.previousTrackCommand.addTargetWithHandler { _ ->
            onSkipPrevious?.invoke()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.playCommand.setEnabled(true)
        cc.pauseCommand.setEnabled(true)
        cc.togglePlayPauseCommand.setEnabled(true)
        cc.stopCommand.setEnabled(true)
        cc.nextTrackCommand.setEnabled(true)
        cc.previousTrackCommand.setEnabled(true)

        isActive = true
    }

    actual fun deactivate() {
        if (!isActive) return
        log.info { "Deactivating media session" }

        val cc = MPRemoteCommandCenter.sharedCommandCenter()
        cc.playCommand.removeTarget(null)
        cc.pauseCommand.removeTarget(null)
        cc.togglePlayPauseCommand.removeTarget(null)
        cc.stopCommand.removeTarget(null)
        cc.nextTrackCommand.removeTarget(null)
        cc.previousTrackCommand.removeTarget(null)

        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null

        isActive = false
    }

    actual fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        MPNowPlayingInfoCenter.defaultCenter().playbackState =
            if (isPlaying) MPNowPlayingPlaybackStatePlaying
            else MPNowPlayingPlaybackStatePaused

        // Also update the playback rate in nowPlayingInfo
        val info = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo?.toMutableMap()
            ?: mutableMapOf<Any?, Any?>()
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (isPlaying) 1.0 else 0.0
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    actual fun updateMetadata(metadata: PlaybackMetadata) {
        if (!isActive) return

        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to metadata.title,
            MPMediaItemPropertyArtist to metadata.displaySubtitle,
            MPNowPlayingInfoPropertyPlaybackRate to if (metadata.isPlaying) 1.0 else 0.0,
        )
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info

        isPlaying = metadata.isPlaying
        MPNowPlayingInfoCenter.defaultCenter().playbackState =
            if (metadata.isPlaying) MPNowPlayingPlaybackStatePlaying
            else MPNowPlayingPlaybackStatePaused
    }

    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
}
