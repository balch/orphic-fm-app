package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * A section's own Pulsar delay, standing in for the vibe's [VibeEffects] values while the section
 * plays. Each field is optional: null keeps the vibe's value. On a transition the delay glides
 * toward the next section's values over the pre-roll, so a time change bends the echoes' pitch
 * instead of jumping; with no pre-roll it snaps at the boundary.
 *
 * @param timeA Tap A time, 0-1 (the [VibeEffects.delayTimeA] scale: 0.01 + v * 1.99 seconds).
 * @param timeB Tap B time, 0-1.
 * @param feedback Repeats, 0-0.95.
 */
@Serializable
data class SectionDelay(
    val timeA: Float? = null,
    val timeB: Float? = null,
    val feedback: Float? = null,
) {
    init {
        require(timeA == null || timeA in 0f..1f) { "SectionDelay.timeA must be 0..1, got $timeA" }
        require(timeB == null || timeB in 0f..1f) { "SectionDelay.timeB must be 0..1, got $timeB" }
        require(feedback == null || feedback in 0f..0.95f) { "SectionDelay.feedback must be 0..0.95, got $feedback" }
    }
}
