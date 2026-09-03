package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Section-scoped storm ambience: a rain/rumble bed plus a chance of lightning strikes, layered
 * under the beat while [Section.weather] is non-null. Crossfades with the same
 * `section_macro_value` lerp the four macros use — swells in over an entry pre-roll, drains out
 * over the walk-back; `null` reads as all-zero (no storm).
 *
 * @param rain Rainfall density, 0-1 — drop RATE, not loudness. The mapping is geometric,
 *   so low values are slow, separated impacts (around a dozen a second) and the top is a
 *   solid shower; individual drops stay about as loud throughout. Use [rainLevel] to make
 *   rain quieter, not this — turning `rain` down thins the rainfall instead.
 * @param rumble Low-frequency rolling-thunder bed level, 0-1.
 * @param strikeChance Chance of a lightning strike (crack + rumble tail), rolled once per bar
 *   while the section is active.
 * @param distance Perceived distance for the bed and any strikes it fires, 0 (near, brighter,
 *   louder cracks) to 1 (far, duller, cracks suppressed).
 * @param rainLevel Loudness of the whole rain layer, 0-1, independent of [rain]'s rate —
 *   near drops and the far-field wash together, so their balance holds at any setting.
 *   1 is the full-scale downpour the engine allows; 0 silences the rain without touching
 *   the rumble. `rain` high with this low is a heavy shower heard from indoors; `rain` low
 *   with this at 1 is a few loud, close drops.
 */
@Serializable
data class SectionWeather(
    val rain: Float = 0f,
    val rumble: Float = 0f,
    val strikeChance: Float = 0f,
    val distance: Float = 0.5f,
    val rainLevel: Float = 1f,
) {
    init {
        require(rain in 0f..1f) { "SectionWeather.rain must be 0..1, got $rain" }
        require(rumble in 0f..1f) { "SectionWeather.rumble must be 0..1, got $rumble" }
        require(strikeChance in 0f..1f) { "SectionWeather.strikeChance must be 0..1, got $strikeChance" }
        require(distance in 0f..1f) { "SectionWeather.distance must be 0..1, got $distance" }
        require(rainLevel in 0f..1f) { "SectionWeather.rainLevel must be 0..1, got $rainLevel" }
    }
}
