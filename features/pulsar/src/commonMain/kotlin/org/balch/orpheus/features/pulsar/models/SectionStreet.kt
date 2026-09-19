package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Section-scoped street sounds: beat-locked footsteps and a steam train pulling away across the
 * section. Crossfades with the macro pre-roll like [SectionWeather]; null = no street.
 *
 * @param footsteps Footstep level, 0-1.
 * @param pace [FootstepPace.WALK] steps on every beat, [FootstepPace.RUN] on every 8th.
 * @param footstepEcho How much of each step reaches the vibe's reverb and delay, 0-1.
 * @param train Train level, 0-1. It starts close and slow as the section begins, with a
 *   whistle, and has faded by the section's end.
 */
@Serializable
data class SectionStreet(
    val footsteps: Float = 0f,
    val pace: FootstepPace = FootstepPace.WALK,
    val footstepEcho: Float = 0f,
    val train: Float = 0f,
) {
    init {
        require(footsteps in 0f..1f) { "SectionStreet.footsteps must be 0..1, got $footsteps" }
        require(footstepEcho in 0f..1f) { "SectionStreet.footstepEcho must be 0..1, got $footstepEcho" }
        require(train in 0f..1f) { "SectionStreet.train must be 0..1, got $train" }
    }
}

@Serializable
enum class FootstepPace { WALK, RUN }
