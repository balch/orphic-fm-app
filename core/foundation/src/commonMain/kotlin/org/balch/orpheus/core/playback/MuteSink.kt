package org.balch.orpheus.core.playback

/**
 * Translates [PlaybackState] transitions to the underlying mute mechanism.
 *
 * Both apps use [AppMuteSink] (writes AppSymbol.MUTED). The `fun interface`
 * shape leaves room for tests/fakes to plug in lambdas.
 *
 * Convention: Playing → unmute, Paused/Stopped → mute.
 */
fun interface MuteSink {
    fun apply(state: PlaybackState)
}
