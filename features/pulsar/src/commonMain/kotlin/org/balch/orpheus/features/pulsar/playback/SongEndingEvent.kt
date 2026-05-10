package org.balch.orpheus.features.pulsar.playback

/**
 * Events emitted by the Pulsar song-ending pipeline.
 * Subscribers (analytics, custom advancers) listen via
 * [org.balch.orpheus.features.pulsar.PulsarFeature.songEndingEvents].
 */
sealed interface SongEndingEvent {
    /** The outro request has been issued; the next bar will route into the final section. */
    data class OutroTriggered(val vibeName: String) : SongEndingEvent

    /** The final section has finished. The default advancer (if installed) loads the next vibe. */
    data class SongEnded(val vibeName: String) : SongEndingEvent
}
