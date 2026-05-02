package org.balch.orpheus.core.playback

/** Direction for next/previous media-button events. */
enum class SkipDirection { NEXT, PREVIOUS }

/**
 * Optional handler for hardware/system skip-next/skip-previous commands.
 * Apps that have no notion of "tracks" (Orpheus without Pulsar) can omit.
 */
fun interface SkipHandler {
    fun onSkip(direction: SkipDirection)
}
