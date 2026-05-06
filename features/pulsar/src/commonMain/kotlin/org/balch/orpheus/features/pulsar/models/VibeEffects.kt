package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Per-vibe effect tuning for the dedicated Pulsar delay and reverb.
 * These set the character of the DEEP knob's wet signal.
 *
 * All values 0-1.
 *
 * @param delayTimeA Tap A delay time. Low = tight slapback, high = spacious echoes.
 * @param delayTimeB Tap B delay time. Offset from A creates rhythmic interest.
 * @param delayFeedback How many repeats. 0.3=subtle, 0.5=moderate, 0.7+=runaway.
 * @param delayDamping High-frequency rolloff per repeat. Low = bright, high = dark/warm.
 * @param reverbSize Room size / decay length. 0.3=room, 0.6=hall, 0.9=cathedral.
 * @param reverbDamping Low-pass damping on reverb tail. Higher = darker tail.
 * @param reverbBrightness Overall brightness of reverb. Low = warm/muted, high = shimmery.
 * @param deepFloor Minimum DEEP multiplier when SPACE=0. Prevents effects from fully disappearing.
 */
@Serializable
data class VibeEffects(
    val delayTimeA: Float = 0.3f,
    val delayTimeB: Float = 0.35f,
    val delayFeedback: Float = 0.4f,
    val delayDamping: Float = 0.5f,
    val reverbSize: Float = 0.6f,
    val reverbDamping: Float = 0.5f,
    val reverbBrightness: Float = 0.5f,
    val deepFloor: Float = 0.3f,
)
