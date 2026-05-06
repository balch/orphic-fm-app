package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Rhythmic comping style for CHORDAL tracks.
 * Each preset maps to a hardcoded 16-step velocity template + default voicing.
 *
 * The C++ enum reserves id 3 for a future parametric Custom style (Phase 2).
 * Kotlin does not expose that slot today — adding a subclass before the
 * engine supports it would silently produce no output.
 */
@Serializable
sealed class CompingStyle {
    /** Sustained single note — classic pad. No rhythmic stabs. */
    @Serializable data object PAD : CompingStyle()

    /** Syncopated 16th-note stabs — classic funk/soul feel. */
    @Serializable data object FUNK_STABS : CompingStyle()

    /** Hits on beats 1 and 3 — rock downbeat comping. */
    @Serializable data object ROCK_DOWNBEATS : CompingStyle()

    /** Off-beat accents on the "and" of every beat — ska/reggae upstroke feel. */
    @Serializable data object SKA_UPSTROKES : CompingStyle()

    /** Triplet-feel shuffle on dotted-8th/16th — blues/swing comping. */
    @Serializable data object BLUES_SHUFFLE : CompingStyle()

    /** Syncopated, busy comping with ghost hits — jazz piano comping. */
    @Serializable data object JAZZ_COMP : CompingStyle()

    /** Strong accent on beat 2 and 4 only — classic reggae skank. */
    @Serializable data object REGGAE_SKANK : CompingStyle()

    /** Dense 8th-note stabs with heavy accents — gospel/soul comping. */
    @Serializable data object GOSPEL_STABS : CompingStyle()

    /** Engine id for the C++ CompingStyleId enum. Keep in sync with orpheus_unit_pulsar.h. */
    val engineId: Int
        get() = when (this) {
            PAD -> 0
            FUNK_STABS -> 1
            ROCK_DOWNBEATS -> 2
            SKA_UPSTROKES -> 4
            BLUES_SHUFFLE -> 5
            JAZZ_COMP -> 6
            REGGAE_SKANK -> 7
            GOSPEL_STABS -> 8
        }
}

/**
 * How many notes to stack on each chord stab.
 */
@Serializable
enum class VoicingType {
    ROOT_ONLY,      // single root note (pad mode)
    ROOT_FIFTH,     // power chord (root + 5th)
    TRIAD,          // root + 3rd + 5th
    SEVENTH,        // triad + 7th
    OCTAVE_STACK,   // root + octave
}

/**
 * How a CHORDAL track's voice renders chord voicings.
 */
@Serializable
enum class ArpMode {
    /** CHD engine: use native chord. Monophonic engines: arpeggiate. */
    AUTO,
    /** Force arpeggio on every engine — rolled/strummed chord effect. */
    ALWAYS,
    /** Force single root note — CHORDAL track plays root only, no chord. */
    NEVER,
}

/**
 * Arpeggiator note ordering.
 */
@Serializable
enum class ArpDirection { UP, DOWN, UP_DOWN, RANDOM }

/**
 * Type of fill played at phrase boundaries. All variants are wired through to
 * the C++ comping engine (`pulsar_comping.h`).
 */
@Serializable
enum class FillType {
    NONE,
    ASCENDING_ARP,   // chord tones walk up through the bar with rising velocity
    DESCENDING_ARP,  // chord tones walk down with fading velocity (root↑ → 5 → 3 → root)
    TURNAROUND,      // bluesy 4-beat landing (root → 3 → ♭7 → root) building into the next bar
    DOUBLE_TIME,     // every step plays a chord tone (root/3/root/5), accented downbeats
    STAB_FLURRY,     // empty first half, dense rising stabs in the second — build-up feel
    DROP_OUT,        // silence except a single accented downbeat — dramatic space
}

/**
 * Phrase-boundary fills on CHORDAL tracks. Every [everyNBars] bars, the
 * current bar gets replaced with the fill variant.
 */
@Serializable
data class CompingFills(
    /** Insert fills every N bars. 0 = disabled. */
    val everyNBars: Int = 0,
    val fillType: FillType = FillType.ASCENDING_ARP,
    /** Chance to skip the fill when a boundary hits (0-1). */
    val skipProbability: Float = 0.0f,
)

/**
 * Probabilistic per-bar variations for CHORDAL tracks — the "Keith Richards" layer.
 * All probabilities scaled by complexity (complexity=0 → no variations).
 */
@Serializable
data class CompingHumanization(
    /** Chance per non-anchor active step to be dropped for this bar. */
    val dropProbability: Float = 0.0f,
    /** Chance per inactive step to become a low-velocity ghost stab. */
    val ghostProbability: Float = 0.0f,
    /** Chance per non-anchor active step to shift ±12 semitones. */
    val octaveJumpProbability: Float = 0.0f,
    /** Chance per active step to add extension interval (+2 or +5 semis). */
    val extensionProbability: Float = 0.0f,
)

/**
 * Voicing stance for chord stabs — which chord tone is the "lowest."
 * Drives CHD engine morph parameter or reorders arp sequence on mono engines.
 */
@Serializable
enum class SectionInversion {
    FOLLOW_STYLE,    // use whatever the style defaults to (root position)
    ROOT_POSITION,   // lowest note = root
    FIRST_INVERSION, // lowest note = 3rd
    SECOND_INVERSION,// lowest note = 5th
    OPEN_VOICING,    // spread across 2 octaves (root low, upper tones spread)
}

/**
 * Comping configuration for [TrackRole.Chordal] tracks.
 * Evolution (inversions, humanization, fills) arrives in Phase 2+; for now,
 * Phase 1 ships static comping that follows the progression.
 *
 * @param style The rhythmic/voicing preset (or custom pattern).
 * @param arpMode How the CHORDAL track renders chord voicings (native vs arpeggiated).
 * @param arpSpeed 0 = slow (each note fills step duration), 1 = fast (~15ms per note, near-simultaneous).
 * @param arpDirection Arpeggiator note ordering.
 */
@Serializable
data class ChordComping(
    val style: CompingStyle = CompingStyle.PAD,
    val arpMode: ArpMode = ArpMode.AUTO,
    val arpSpeed: Float = 0.2f,
    val arpDirection: ArpDirection = ArpDirection.UP,
    val sectionInversion: SectionInversion = SectionInversion.FOLLOW_STYLE,
    val humanization: CompingHumanization = CompingHumanization(),
    val fills: CompingFills = CompingFills(),
)
