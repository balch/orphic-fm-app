package org.balch.orpheus.core.playback

/**
 * Single source of truth for whether the app is supposed to be making sound.
 *
 * - [Stopped]: engine off, MediaSession inactive. No notification.
 * - [Playing]: engine on, audio audible.
 * - [Paused]: engine on, audio muted via [MuteSink], MediaSession kept alive
 *   so the user can resume from notification / lock screen / Auto.
 *
 * All transitions go through PlaybackController.play/pause/stop. Nothing
 * else may write to this state — that's the architectural property.
 */
sealed interface PlaybackState {
    data object Stopped : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
}
