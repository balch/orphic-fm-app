package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Min/max range that a macro knob maps to for a specific parameter.
 * @param min Value when the macro is at 0.
 * @param max Value when the macro is at 1.
 */
@Serializable
data class MacroTarget(
    val min: Float,
    val max: Float,
)

/**
 * How the 4 macro knobs (energy, complexity, space, mood) affect a track.
 * Each field maps a macro to a parameter with a min/max range.
 * Use the built-in presets (RHYTHM, MELODIC, EFFECT, WILD) or customize.
 *
 * - RHYTHM: Strong energy response, minimal swing/variation (drums stay steady)
 * - MELODIC: Full-range energy+density, moderate swing, rich mood response (bass, keys)
 * - EFFECT: Low energy response, high space/decay (pads, textures — they recede when energy rises)
 * - WILD: Extreme ranges on everything (experimental, unpredictable tracks)
 */
@Serializable
data class TrackMacroMap(
    val energyVolume: MacroTarget,
    val energyDensity: MacroTarget,
    val complexitySwing: MacroTarget,
    val complexityVariation: MacroTarget,
    val spaceDecay: MacroTarget,
    val moodHarmonics: MacroTarget,
    val moodTimbre: MacroTarget,
) {
    companion object {
        val RHYTHM = TrackMacroMap(
            energyVolume = MacroTarget(0.7f, 1.0f),
            energyDensity = MacroTarget(0.4f, 0.8f),
            complexitySwing = MacroTarget(0.0f, 0.1f),
            complexityVariation = MacroTarget(0.0f, 0.15f),
            spaceDecay = MacroTarget(0.2f, 0.5f),
            moodHarmonics = MacroTarget(0.3f, 0.6f),
            moodTimbre = MacroTarget(0.2f, 0.5f),
        )

        val MELODIC = TrackMacroMap(
            energyVolume = MacroTarget(0.5f, 1.0f),
            energyDensity = MacroTarget(0.4f, 0.9f),
            complexitySwing = MacroTarget(0.0f, 0.15f),
            complexityVariation = MacroTarget(0.0f, 0.3f),
            spaceDecay = MacroTarget(0.2f, 0.5f),
            moodHarmonics = MacroTarget(0.3f, 0.7f),
            moodTimbre = MacroTarget(0.4f, 0.8f),
        )

        val EFFECT = TrackMacroMap(
            energyVolume = MacroTarget(0.2f, 0.5f),
            energyDensity = MacroTarget(0.05f, 0.25f),
            complexitySwing = MacroTarget(0.0f, 0.2f),
            complexityVariation = MacroTarget(0.0f, 0.25f),
            spaceDecay = MacroTarget(0.5f, 0.9f),
            moodHarmonics = MacroTarget(0.3f, 0.7f),
            moodTimbre = MacroTarget(0.4f, 0.8f),
        )

        val WILD = TrackMacroMap(
            energyVolume = MacroTarget(0.1f, 0.4f),
            energyDensity = MacroTarget(0.05f, 0.3f),
            complexitySwing = MacroTarget(0.0f, 0.5f),
            complexityVariation = MacroTarget(0.1f, 0.6f),
            spaceDecay = MacroTarget(0.4f, 0.8f),
            moodHarmonics = MacroTarget(0.5f, 0.9f),
            moodTimbre = MacroTarget(0.4f, 0.8f),
        )
    }
}

/** Macro multipliers: 1.0=no change, >1=boost, <1=cut, null=inactive. */
@Serializable
data class MacroOverrides(
    val energy: Float? = null,
    val complexity: Float? = null,
    val space: Float? = null,
    val mood: Float? = null,
)

/**
 * Which live macro knob drives a parameter-walk (e.g., DX patch selection
 * via [OrpheusEngine.harmonicsMacroRange]). The integer ordinal is the wire
 * value sent to the C++ engine; do not reorder without bumping the routing.
 */
@Serializable
enum class MacroSource {
    NONE,
    ENERGY,
    COMPLEXITY,
    SPACE,
    MOOD,
}
