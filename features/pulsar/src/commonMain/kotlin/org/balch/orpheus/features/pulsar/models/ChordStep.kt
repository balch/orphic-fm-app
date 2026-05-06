package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

internal fun validateProgression(steps: List<ChordStep>, where: String) {
    require(steps.size in 1..8) {
        "$where size must be 1..8, got ${steps.size}"
    }
    require(steps.all { it.degree in 0..6 }) {
        "$where degrees must be 0..6 (I-VII), got ${steps.map { it.degree }}"
    }
    require(steps.all { it.glideRate in 0f..1f }) {
        "$where glideRate must be 0..1, got ${steps.map { it.glideRate }}"
    }
}

/**
 * One chord in a progression.
 * @param degree Scale degree 0..6 (I-VII).
 * @param glideRate Portamento applied when transitioning *into* this chord, 0..1.
 *   0 = instant change (default), 0.3 = smooth, 0.6+ = very slow slide.
 *   Only takes effect on tracks whose role honors the chord progression.
 */
@Serializable
data class ChordStep(
    val degree: Int,
    val glideRate: Float = 0f,
)

/**
 * Convenience builder: convert a series of scale degrees into [ChordStep]s
 * with no glide. Use this for the common `progression` form:
 *
 *   `customProgression = chords(0, 3, 5, 6)`
 *
 * For per-chord glides, build the list explicitly:
 *
 *   `customProgression = listOf(ChordStep(0), ChordStep(3, glideRate = 0.4f), ...)`
 */
fun chords(vararg degrees: Int): List<ChordStep> = degrees.map { ChordStep(it) }
