package org.balch.orpheus.core.features

/**
 * Controls how Pulsar playback is started in each app context.
 */
enum class PulsarPlaybackMode {
    /** Pulsar auto-starts on vibe load; mix knob gates audibility (Orpheus). */
    MIX_GATED,
    /** Pulsar requires explicit play/pause via toggleGlobalPause (DJ app). */
    EXPLICIT,
}
