package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Preset rhythm density levels mapped to common drum patterns.
 * Used as the [GenreProfile.rhythmDensity] value.
 */
@Serializable
enum class RhythmPattern(val density: Float) {
    SPARSE(0.0f),
    FOUR_ON_FLOOR(0.33f),
    BACKBEAT(0.67f),
    DENSE_16TH(1.0f),
}

/**
 * Chord progression character — determines how chords move through a bar.
 * - POP: Standard I-IV-V-vi, upbeat and familiar
 * - SAD: Minor-leaning, descending tendencies
 * - JAZZ: Extended voicings, ii-V-I movement, chromatic passing
 * - BLUES: 12-bar blues feel, dominant 7th movement
 * - DRONE: Static root, minimal chord movement (ambient/meditative)
 * - MODAL: Stays within a single mode, avoids traditional resolution
 * - DARK: Diminished and minor inflections, dissonant tendency
 * - ASCENDING: Upward chord movement, building energy
 */
@Serializable
enum class ProgressionStyle {
    POP,
    SAD,
    JAZZ,
    BLUES,
    DRONE,
    MODAL,
    DARK,
    ASCENDING,
}

/**
 * How often the chord progression resets to its original state.
 * Bounds cumulative Markov drift so progressions evolve then come home.
 */
@Serializable
enum class ProgressionAnchor(val barsBetweenResets: Int) {
    NONE(0),        // never reset — unbounded drift
    EVERY_2(2),     // tight leash
    EVERY_4(4),     // typical 4-bar phrase
    EVERY_8(8),     // 8-bar section
    EVERY_16(16),   // full verse/chorus
}

/**
 * Genre-level musical parameters shared across all 8 tracks.
 *
 * @param swingAmount Shuffle feel 0-1. 0 = straight, 0.1 = subtle groove, 0.3+ = heavy swing.
 * @param ghostProbability Chance of inserting quiet ghost notes 0-1. Adds human feel.
 *   0.1 = sparse, 0.3 = funky, 0.5+ = busy.
 * @param noteRangeLow Default MIDI note floor for melodic tracks (overridden per-track if set).
 *   36 = C2, 48 = C3. Lower = deeper bass.
 * @param noteRangeHigh Default MIDI note ceiling. 60 = C4, 72 = C5.
 * @param rhythmDensity Overall rhythm feel. Use [RhythmPattern] values:
 *   SPARSE(0.0), FOUR_ON_FLOOR(0.33), BACKBEAT(0.67), DENSE_16TH(1.0).
 * @param progressionStyle Chord progression character (POP, BLUES, JAZZ, MODAL, DRONE, etc.)
 *   Selects both the default chord sequence and the Markov transition matrix.
 *   The sequence is overridden by [customProgression] when set; the matrix is
 *   overridden by [chordTransitionMatrix] when set.
 * @param chordsPerBar How many chord changes per bar. 1 = static, 2 = standard, 4 = busy.
 * @param chordTransitionMatrix Optional 7x7 Markov matrix for chord transitions (I-VII).
 *   Build with [chordMatrix]. Null = use [progressionStyle]'s default matrix.
 * @param customProgression Optional explicit chord sequence. Each entry is a [ChordStep]
 *   carrying a scale degree 0-6 (I-VII) and an optional per-chord glide. Overrides
 *   the [progressionStyle]'s template sequence but still uses its matrix unless
 *   [chordTransitionMatrix] is also supplied. Size 1..12 (e.g. a literal 12-bar blues). Useful for "hang-on-tonic"
 *   feels and other vibe-specific forms:
 *   ```
 *   customProgression = chords(0, 0, 0, 6)  // i-i-i-VII roots reggae (no glide)
 *   customProgression = listOf(                 // pedal-steel slide on the V
 *       ChordStep(0), ChordStep(5), ChordStep(3, glideRate = 0.45f), ChordStep(4)
 *   )
 *   ```
 */
@Serializable
data class GenreProfile(
    val swingAmount: Float,
    val ghostProbability: Float,
    val noteRangeLow: Int,
    val noteRangeHigh: Int,
    val rhythmDensity: Float,
    val progressionStyle: ProgressionStyle = ProgressionStyle.POP,
    val chordsPerBar: Int = 2,
    @Serializable(with = ChordTransitionMatrixSerializer::class)
    val chordTransitionMatrix: List<Float>? = null,
    val customProgression: List<ChordStep>? = null,
) {
    init {
        customProgression?.let { validateProgression(it, "GenreProfile.customProgression") }
        chordTransitionMatrix?.let {
            require(it.size == 49) {
                "GenreProfile.chordTransitionMatrix must have 49 values (7x7), got ${it.size}"
            }
        }
    }
}

/**
 * Decodes the canonical flat 49-float array AND a nested 7x7 array of rows — agents sometimes
 * emit a Markov matrix as `rows` (readable, matches how they reason about it) instead of the flat
 * form. Flattens row-major (`matrix[7]` == row 1's first value) before [GenreProfile.init]'s
 * `size == 49` check runs, so a malformed nested shape (e.g. 6 rows of 7) still fails with the
 * existing "must have 49 values" message. Encoding always emits the flat form.
 */
private object ChordTransitionMatrixSerializer :
    JsonTransformingSerializer<List<Float>>(ListSerializer(Float.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonArray || element.firstOrNull() !is JsonArray) return element
        return JsonArray(element.flatMap { it as JsonArray })
    }
}
