package org.balch.orpheus.core.audio

/**
 * Cross-feature signal: is the sleep timer currently fading audio out?
 * Implemented in :features:timer; consumed by :features:pulsar so that
 * Pulsar's song-ending ramp defers when the timer is already pulling
 * master volume to silence.
 *
 * Exists in core/foundation to avoid a Pulsar↔Timer module cycle.
 */
interface TimerFadeStatusProvider {
    fun isFading(): Boolean
}
