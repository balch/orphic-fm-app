package org.balch.orpheus.features.pulsar

import kotlinx.serialization.Serializable

private fun validateProgression(steps: List<ChordStep>, where: String) {
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

interface VibeProvider {
    val vibe: Vibe
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

/**
 * One note in a bass lick pattern.
 * @param scaleDegree Index into the current scale (0 = root, 1 = 2nd degree, etc.)
 * @param duration Note length in beats (0.25 = 16th, 0.5 = 8th, 1.0 = quarter)
 * @param velocity Hit strength 0-1 (lower = ghost note feel)
 * @param glideRate Optional per-note portamento. `-1f` (default) = use the track's
 *   own [TrackVoice.glideRate]. A value in `0f..1f` overrides for this step only:
 *   0 = instant pitch jump; 0.3 = smooth; 0.6+ = very slow slide. The sentinel
 *   matches the C++ engine's `glide_rate` convention exactly (no boxing, no
 *   marshalling translation).
 */
@Serializable
data class LickStep(
    val scaleDegree: Int,
    val duration: Float,
    val velocity: Float = 0.8f,
    val glideRate: Float = -1f,
)

/**
 * A repeating melodic figure (bass riff). Assign to a [Vibe] and set
 * `lickMode = LickMode.Fill` (or `Squash`) on the track that should play it.
 * @param steps The note sequence. Max 32 steps.
 * @param loopLength Total loop length in **beats**. When larger than the sum of step
 *   durations, the extra time is silence (rest padding). E.g. a 4-beat lick with
 *   `loopLength = 8` plays 4 beats of notes then 4 beats of rest per cycle.
 *   Default (0) = no rest, notes fill the entire pattern.
 */
@Serializable
data class Lick(
    val steps: List<LickStep>,
    val loopLength: Int = 0,
) {
    init {
        require(steps.size <= MAX_LICK_STEPS) {
            "Lick steps size ${steps.size} exceeds MAX_LICK_STEPS=$MAX_LICK_STEPS"
        }
        require(steps.all { it.glideRate == -1f || it.glideRate in 0f..1f }) {
            "LickStep.glideRate must be -1 (use track default) or in 0..1, got " +
                steps.map { it.glideRate }
        }
    }

    companion object {
        const val MAX_LICK_STEPS = 32
    }
}

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

/** Plaits engine indices — matches C++ kEngineBusType[] order. */
@Serializable
enum class Engine(val id: Int) {
    VCF(0),    // VirtualAnalogVCF — bass filter sweep
    PD(1),     // PhaseDistortion
    DX(2),     // SixOp FM1
    DX2(3),    // SixOp FM2
    DX3(4),    // SixOp FM3
    TRN(5),    // WaveTerrain
    ENS(6),    // StringMachine (ensemble)
    NES(7),    // Chiptune
    VA(8),     // VirtualAnalog
    WSH(9),    // Waveshaping
    FM(10),    // FM
    GRN(11),   // Grain
    ADD(12),   // Additive
    WTB(13),   // Wavetable
    CHD(14),   // Chord
    SPK(15),   // Speech
    SWM(16),   // Swarm
    NSE(17),   // Noise (percussive)
    PAR(18),   // Particle (percussive)
    STR(19),   // String
    MOD(20),   // Modal (tuned percussion)
    BD(21),    // BassDrum
    SD(22),    // SnareDrum
    HH(23),    // HiHat
}

/**
 * Envelope shape categories — controls attack/decay/sustain character.
 * - RHYTHM: Short, punchy (kicks, snares, hats)
 * - MELODIC: Medium attack, sustain, clean release (bass, keys)
 * - EFFECT: Slow attack, long tail (pads, textures)
 * - WILD: Unpredictable, macro-driven (experimental tracks)
 * - DRONE: Very slow attack, infinite sustain while gate held (ambient pads)
 */
@Serializable
enum class EnvelopeProfile(val id: Int) {
    RHYTHM(0),
    MELODIC(1),
    EFFECT(2),
    WILD(3),
    DRONE(4),
}

/**
 * How a track's pattern evolves across bars.
 * - REPEAT: Same pattern every bar (driving grooves, anchoring elements)
 * - MUTATE: Slightly varies each bar (keeps interest without losing feel)
 * - FILL: Adds fills at phrase boundaries (snare rolls, tom fills)
 * - CALL_RESPONSE: Alternates between two complementary patterns
 * - INDEPENDENT: Fully regenerated each bar (textures, atmospheric elements)
 */
@Serializable
enum class BarStrategy(val id: Int) {
    REPEAT(0),
    MUTATE(1),
    FILL(2),
    CALL_RESPONSE(3),
    INDEPENDENT(4),
}

/**
 * How a melodic track responds to the chord progression at playback time.
 */
@Serializable
enum class ChordFollow {
    /** Default — transpose pattern notes by the current chord degree. */
    FOLLOW,
    /** Override each stab with the chord root (simple chord-following bass). */
    ROOT_ONLY,
    /** Ignore progression — play pattern as generated (drone/pedal). */
    FIXED,
}

/**
 * Track behavior role — determines pattern generation, pitch handling, and which
 * evolution dimensions are available. Role-specific fields live on their subclass,
 * so invalid combinations (e.g. percussive tracks with comping) are unrepresentable.
 */
@Serializable
sealed class TrackRole {
    /** Drums — fixed pitch, rhythm patterns. */
    @Serializable
    data object Percussive : TrackRole()

    /** Single notes — scale-quantized, lick-capable. */
    @Serializable
    data class Melodic(
        /** How this track responds to the chord progression. */
        val chordFollow: ChordFollow = ChordFollow.FOLLOW,
        /** How this track maps the vibe's lick to sequencer steps. */
        val lickMode: LickMode = LickMode.None,
    ) : TrackRole()

    /** Chord voicings — progression-following, comping patterns. */
    @Serializable
    data class Chordal(
        /** Rhythmic voicing configuration. */
        val comping: ChordComping = ChordComping(),
        /** How this track responds to the chord progression. */
        val chordFollow: ChordFollow = ChordFollow.FOLLOW,
    ) : TrackRole()

    /** Engine id for C++ TrackRole enum. Keep in sync with orpheus_unit_pulsar.h. */
    val engineId: Int
        get() = when (this) {
            Percussive -> 0
            is Melodic -> 1
            is Chordal -> 2
        }
}

/**
 * How a track maps the vibe's [Lick] to sequencer steps.
 * Only meaningful when [TrackVoice.role] is [TrackRole.MELODIC].
 */
@Serializable
sealed class LickMode {
    /** No lick — track uses generative patterns. */
    @Serializable
    data object None : LickMode()

    /** Compress lick to fit within one bar. In 32-step mode, the second
     *  half is handled by the bar strategy. */
    @Serializable
    data object Squash : LickMode()

    /** Lick spans the full step count as a single continuous phrase.
     *  Bypasses the bar 1/bar 2 split entirely. */
    @Serializable
    data object Fill : LickMode()
}

/**
 * How moved notes relate to their melodic content during MUTATE transforms.
 * Only meaningful for [TrackRole.MELODIC] tracks with rhythmic evolution.
 */
@Serializable
enum class NoteFollowMode {
    /** Notes keep their identity, shift in time with the step. */
    SLIDE,
    /** Rhythmic positions keep their role, notes get redistributed. */
    CONTOUR,
    /** Blend — notes partially follow position, partially follow identity. */
    BLEND,
}

/**
 * Pitch/voicing evolution — role-specific.
 * Use [Contour] for MELODIC tracks (Markov pitch drift),
 * [Voicing] for CHORDAL tracks (inversion shifts, chord substitution).
 */
@Serializable
sealed class PitchEvolution {
    /** Markov contour for MELODIC tracks — second-order pitch drift. */
    @Serializable
    data class Contour(
        val driftRange: Float = 0.1f,
    ) : PitchEvolution()

    /** Voicing evolution for CHORDAL tracks — inversion shifts and chord substitution. */
    @Serializable
    data class Voicing(
        val tensionResponse: Float = 1.0f,
    ) : PitchEvolution()
}

/**
 * Rhythmic position evolution via MUTATE transforms (SHIFT/SYNCOPATE/RESHAPE).
 * Works for all [TrackRole]s.
 */
@Serializable
data class RhythmicEvolution(
    /** How much tension drives transform intensity.
     *  0 = always SHIFT, 1 = full SHIFT→SYNCOPATE→RESHAPE arc. */
    val tensionResponse: Float = 1.0f,
    /** How moved notes relate to their melodic content. Only meaningful for MELODIC tracks. */
    val noteFollow: NoteFollowMode = NoteFollowMode.SLIDE,
)

/**
 * Per-track evolution config. Composes rhythmic (position) and pitch (content)
 * evolution independently. Both can be active simultaneously.
 */
@Serializable
data class Evolution(
    /** Rhythmic position evolution via MUTATE transforms. Null = no rhythmic evolution. */
    val rhythmic: RhythmicEvolution? = null,
    /** Pitch/voicing evolution. Null = no pitch evolution. */
    val pitch: PitchEvolution? = null,
)

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
 * Comping configuration for [TrackRole.CHORDAL] tracks.
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

/**
 * One of 8 tracks in a Vibe. Each track is a voice with its own engine, density,
 * envelope shape, and effect sends. Convention: tracks 0-2 = rhythm (kick/snare/hat),
 * 3-4 = melodic, 5-6 = texture/effects, 7 = wild card — but any track can use any engine.
 *
 * ## Quick tuning guide
 * - **Volume/pan/density** shape the mix and busyness of this track.
 * - **harmonics/timbre/morph** are the Plaits engine knobs (meaning varies by engine).
 * - **delaySend/reverbSend** route this track to the vibe's dedicated effects (scaled by DEEP).
 * - **modLfo*** add slow parameter drift for texture tracks.
 * - **holdProbability** creates sustained/tied notes for pads and drones.
 *
 * @param engineEdm Plaits engine used when the vibe leans EDM (high energy).
 * @param engineSpace Plaits engine used when the vibe leans Space (low energy).
 *   The engine crossfades between EDM and Space based on the energy macro.
 * @param role Track behavior role — determines pattern generation and pitch handling.
 * @param volume Track volume 0-1. Start around 0.8 for leads, 0.3-0.5 for texture.
 * @param pan Stereo position: -1.0 = hard left, 0.0 = center, 1.0 = hard right.
 * @param density Probability that a step gets a note, 0-1.
 *   0.5 = half the steps fire. Rhythm tracks want 0.3-0.6, texture tracks 0.05-0.2.
 * @param harmonics Engine-specific: usually harmonic content or brightness.
 * @param timbre Engine-specific: usually tone color or filter position.
 * @param morph Engine-specific: usually waveshape or model parameter.
 * @param envelopeProfile Envelope shape category — determines attack/decay character.
 * @param macroMap How the 4 macro knobs (energy/complexity/space/mood) affect this track.
 * @param barStrategy How the pattern evolves across bars (REPEAT, MUTATE, FILL, etc.)
 * @param evolutionWeight How much tension-driven timbre evolution affects this track.
 *   -1 = auto (1.0 for MELODIC macroMap, 0.0 for others). 0-1 for explicit control.
 * @param modLfoRate LFO speed for slow parameter modulation. 0.0-1.0, useful range 0.03-0.3.
 * @param modLfoDepth LFO modulation depth. 0 = off. 0.3-0.7 for subtle movement.
 * @param modLfoShape LFO waveshape. 0 = sine-like, 0.5 = triangle-like, 1.0 = square-like.
 * @param modLfoCoupling How much the LFO affects multiple parameters together.
 * @param holdProbability Chance of generating a sustained/tied note instead of a single hit.
 *   0.0 = never, 0.9 = mostly sustained. Great for pads and drones.
 * @param holdLengthMin Minimum held note length in steps.
 * @param holdLengthMax Maximum held note length in steps.
 * @param delaySend Send level to the vibe's delay effect, 0-1.
 * @param reverbSend Send level to the vibe's reverb effect, 0-1.
 * @param noteRangeLow MIDI note floor for this track (null = use genre default).
 * @param noteRangeHigh MIDI note ceiling for this track (null = use genre default).
 * @param reverbBrightness Per-track reverb color: 0 = dark/warm, 1 = bright/shimmery.
 * @param delayFeedback Per-track delay feedback override (null = use vibe's global setting).
 * @param glideRate Portamento speed: 0 = instant, 0.3 = smooth, 1.0 = very slow (~2s).
 * @param lickMode How this track maps the vibe's lick to sequencer steps.
 * @param evolution Per-track evolution config for rhythmic and pitch evolution.
 */
@Serializable
data class TrackVoice(
    val engineEdm: Engine,
    val engineSpace: Engine,
    val role: TrackRole = TrackRole.Percussive,
    val volume: Float = 0.8f,
    val pan: Float = 0.0f,
    val density: Float = 0.5f,
    val harmonics: Float = 0.5f,
    val timbre: Float = 0.5f,
    val morph: Float = 0.5f,
    val envelopeProfile: EnvelopeProfile = EnvelopeProfile.RHYTHM,
    val macroMap: TrackMacroMap = TrackMacroMap.RHYTHM,
    val barStrategy: BarStrategy = BarStrategy.REPEAT,
    val evolutionWeight: Float = -1f,
    val soloBehavior: SoloBehavior? = null,
    val duckingProfile: DuckingProfile? = null,
    val modLfoRate: Float = 0.2f,
    val modLfoDepth: Float = 0.0f,
    val modLfoShape: Float = 0.3f,
    val modLfoCoupling: Float = 0.2f,
    val holdProbability: Float = 0.0f,
    val holdLengthMin: Int = 2,
    val holdLengthMax: Int = 8,
    val delaySend: Float = 0.0f,
    val reverbSend: Float = 0.0f,
    val noteRangeLow: Int? = null,
    val noteRangeHigh: Int? = null,
    val reverbBrightness: Float = 0.5f,
    val delayFeedback: Float? = null,
    val glideRate: Float = 0.0f,
    val evolution: Evolution = Evolution(),
)

/** Convenience: [ChordComping] if this track is [TrackRole.Chordal], else null. */
val TrackVoice.chordComping: ChordComping?
    get() = (role as? TrackRole.Chordal)?.comping

/** Convenience: [LickMode] if this track is [TrackRole.Melodic], else [LickMode.None]. */
val TrackVoice.lickMode: LickMode
    get() = (role as? TrackRole.Melodic)?.lickMode ?: LickMode.None

/** Convenience: chord-follow mode — [ChordFollow.FOLLOW] for percussive tracks. */
val TrackVoice.chordFollow: ChordFollow
    get() = when (val r = role) {
        is TrackRole.Percussive -> ChordFollow.FOLLOW
        is TrackRole.Melodic -> r.chordFollow
        is TrackRole.Chordal -> r.chordFollow
    }

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
 *   [chordTransitionMatrix] is also supplied. Size 1..8. Useful for "hang-on-tonic"
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
    val chordTransitionMatrix: List<Float>? = null,
    val customProgression: List<ChordStep>? = null,
) {
    init {
        customProgression?.let { validateProgression(it, "GenreProfile.customProgression") }
    }
}

/**
 * Pitch/scale modifications applied during high-tension moments.
 * @param octaveShift If true, melodic tracks may shift up an octave at peak tension.
 * @param keyShift Semitones to shift the root at peak tension. 0 = none, 5 = up a 4th.
 * @param halfLick If true, lick plays at double speed during high tension (half-time feel).
 * @param chromaticPassing Probability of inserting chromatic passing tones at high tension, 0-1.
 */
@Serializable
data class TonalTension(
    val octaveShift: Boolean = false,
    val keyShift: Int = 0,
    val halfLick: Boolean = false,
    val chromaticPassing: Float = 0.0f,
)

/**
 * Timbre evolution over the tension cycle. Engine knobs (harmonics, timbre, morph)
 * drift between low/high values as tension rises and falls.
 *
 * @param timbreLow Timbre value at minimum tension.
 * @param timbreHigh Timbre value at peak tension.
 * @param timbreProbability Chance that timbre evolves each cycle, 0-1.
 * @param morphLow Morph at min tension (-1 = use track default).
 * @param morphHigh Morph at peak tension (-1 = use track default).
 * @param morphProbability Chance morph evolves.
 * @param harmonicsLow Harmonics at min tension (-1 = use track default).
 * @param harmonicsHigh Harmonics at peak tension (-1 = use track default).
 * @param harmonicsProbability Chance harmonics evolves.
 * @param attackPoint Where in the tension cycle the peak occurs, 0-1. 0.5 = midpoint.
 * @param releaseSpeed How quickly tension decays after the peak, 0-1. 0.3 = slow, 0.9 = fast snap-back.
 */
@Serializable
data class EvolutionTension(
    val timbreLow: Float = 0.25f,
    val timbreHigh: Float = 0.55f,
    val timbreProbability: Float = 0.7f,
    val morphLow: Float = -1f,
    val morphHigh: Float = -1f,
    val morphProbability: Float = 0.5f,
    val harmonicsLow: Float = -1f,
    val harmonicsHigh: Float = -1f,
    val harmonicsProbability: Float = 0.3f,
    val attackPoint: Float = 0.5f,
    val releaseSpeed: Float = 0.3f,
)

/**
 * Controls musical tension — the build-and-release arc that keeps things interesting.
 * Tension cycles over [innerBars] bars, optionally nested inside a longer [outerBars] cycle.
 *
 * @param innerBars Primary tension cycle length. 4 = tight builds, 8 = longer phrases, 16 = epic arcs.
 * @param outerBars Secondary (macro) tension cycle. 0 = disabled. 16-32 = album-length arcs.
 * @param outerDepth How much the outer cycle modulates the inner. 0-1.
 * @param volume How much tension affects track volumes. 0 = none, 0.3 = subtle, 0.6+ = dramatic.
 * @param timing How much tension affects rhythmic tightness. 0 = none, 0.2 = subtle drift.
 * @param tonal Pitch/scale tension (octave shifts, chromatic passing tones).
 * @param evolution Timbre evolution over the tension cycle (harmonics/timbre/morph drift).
 */
@Serializable
data class TensionProfile(
    val innerBars: Int = 4,
    val outerBars: Int = 0,
    val outerDepth: Float = 0.5f,
    val volume: Float = 0.3f,
    val tonal: TonalTension = TonalTension(),
    val timing: Float = 0.2f,
    val evolution: EvolutionTension = EvolutionTension(),
    val spurtChance: Float = 0.0f,  // per-bar random spurt probability (0 = tension-only)
)

/**
 * A complete beat machine preset — the top-level unit of Pulsar.
 * Defines 8 tracks, a musical key, tempo, macro defaults, effects, and tension arc.
 *
 * ## Tuning workflow
 * 1. Set **bpm**, **rootNote**, **scaleType** for the musical foundation.
 * 2. Set **energy/complexity/space/mood/deep** macro defaults (0-1).
 *    These are the starting positions of the 5 knobs the user can tweak live.
 * 3. Define 8 **tracks** with engines, density, volume, and effect sends.
 * 4. Optionally add a **lick** (bass riff pattern) and set `lickMode = LickMode.Fill` on one track.
 * 5. Tune **effects** for the delay/reverb character.
 * 6. Set **tension** for build-and-release arcs.
 * 7. Optionally add an **arrangement** for section-based structure (verse/chorus/solo).
 *
 * @param name Display name for the vibe selector.
 * @param tracks Exactly 8 tracks. See [TrackVoice] for per-track tuning.
 * @param lick Optional bass riff pattern. Tracks with `lickMode` set to Squash or Fill play this.
 * @param lickMutation How much the lick varies on repeat, 0-1. 0 = exact, 0.5 = moderate drift.
 * @param lickOctave MIDI octave for the lick. -1 = auto (midpoint of noteRange), 0-8 = explicit.
 * @param seed Random seed for pattern generation. Same seed = same patterns. 0 = random.
 * @param bpm Tempo in beats per minute.
 * @param envelopeType Global envelope mode: AD (punchy), TIDES (sustain while held), BLEND (energy-driven mix).
 * @param rootNote Musical root note.
 * @param scaleType Musical scale.
 * @param genre Genre-level parameters (swing, ghost notes, note range, chord style).
 * @param energy Starting energy level 0-1. Higher = louder, denser, more driving.
 * @param complexity Starting complexity 0-1. Higher = more swing, more variation, busier patterns.
 * @param space Starting space level 0-1. Higher = longer decays, more reverb/delay sends.
 * @param mood Starting mood 0-1. Affects harmonics and timbre across tracks.
 * @param deep Starting depth 0-1. Controls wet/dry mix for vibe effects (delay + reverb).
 * @param stepCount Steps per pattern. 16 = standard, 32 = double-length phrases.
 * @param tension Build-and-release arc configuration.
 * @param arrangement Optional section-based structure (verse, chorus, solo, etc.)
 * @param effects Delay and reverb tuning for this vibe.
 */
@Serializable
data class Vibe(
    val name: String,
    val tracks: List<TrackVoice>,
    val lick: Lick? = null,
    val lickMutation: Float = 0.5f,
    val lickOctave: Int = -1,
    val band: Band? = null,
    val seed: Int = 0,
    val bpm: Float,
    val envelopeType: EnvelopeType = EnvelopeType.AD,
    val rootNote: RootNote,
    val scaleType: ScaleType,
    val genre: GenreProfile,
    val energy: Float = 0.5f,
    val complexity: Float = 0.3f,
    val space: Float = 0.4f,
    val mood: Float = 0.5f,
    val deep: Float = 0.5f,
    val stepCount: Int = 16,
    val tension: TensionProfile = TensionProfile(),
    val arrangement: Arrangement? = null,
    val progressionAnchor: ProgressionAnchor = ProgressionAnchor.EVERY_4,
    val progressionDriftRange: Float = 0.5f,
    val effects: VibeEffects = VibeEffects(),
) {
    init {
        require(tracks.size == 8) {
            "Vibe requires exactly 8 tracks, got ${tracks.size}"
        }
    }
}

enum class RootNote(val noteIndex: Int) {
    C(0), C_SHARP(1), D(2), D_SHARP(3),
    E(4), F(5), F_SHARP(6), G(7),
    G_SHARP(8), A(9), A_SHARP(10), B(11),
}

/**
 * Global envelope mode — sets the overall feel for how notes are shaped.
 * - AD: Attack-Decay only. Tight, punchy. Best for EDM, techno, drum-heavy vibes.
 * - TIDES: Attack-Release with sustain while gate is held. Notes breathe and sustain.
 *   Best for ambient, deep, pad-heavy vibes.
 * - BLEND: Energy-driven crossfade between AD and TIDES. High energy = punchy AD,
 *   low energy = loose TIDES. Best for vibes that span a wide dynamic range.
 */
enum class EnvelopeType(val modeIndex: Int) {
    AD(0),
    TIDES(1),
    BLEND(2),
}

enum class ScaleType(val scaleIndex: Int) {
    MINOR(0),
    MAJOR(1),
    PENTATONIC(2),
    PHRYGIAN(3),
    WHOLE_TONE(4),
    CHROMATIC(5),
    DORIAN(6),
    LYDIAN(7),
    MIXOLYDIAN(8),
    HARMONIC_MINOR(9),
    MINOR_PENTATONIC(10),
    HIRAJOSHI(11),
    IN_SEN(12),
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
 * A weighted edge in the section graph.
 * @param targetIndex Index into [Arrangement.sections] for the destination section.
 * @param weight Relative probability of this transition (weights are normalized per section).
 * @param transitionBars If > 0, the macro overrides crossfade smoothly toward the
 *   destination section's overrides over the LAST [transitionBars] bars of the
 *   source section ("pre-roll" model). At the boundary, the destination section
 *   takes over with full character. Default 0 = hard cut at the boundary.
 *
 *   Per-edge so each route can have its own personality:
 *   - chorus lift:    `SectionTransition(STAB, 0.5f, transitionBars = 2)`
 *   - hard verse cut: `SectionTransition(VERSE, 0.5f)` (no ramp)
 *   - long fade-out:  `SectionTransition(DRIFT, 0.2f, transitionBars = 4)`
 */
@Serializable
data class SectionTransition(
    val targetIndex: Int,
    val weight: Float,
    val transitionBars: Int = 0,
)


/**
 * Markov chain config for melodic solo generation. Controls how notes are chosen
 * during solos based on interval probabilities.
 *
 * @param intervalWeights 15 floats: probability of jumping -7 to +7 semitones.
 *   Index 7 = unison (staying on the same note). Higher outer values = wider leaps.
 *   Use the built-in defaults: MELODIC_DEFAULT, RHYTHMIC_DEFAULT, EFFECT_DEFAULT, WILD_DEFAULT.
 * @param restProbability Chance of inserting a rest between notes, 0-1.
 * @param holdProbability Chance of sustaining the current note instead of moving, 0-1.
 * @param densityCurveMin Note density at the start of a solo phrase.
 * @param densityCurveMax Note density at the climax of a solo phrase.
 * @param rhythmVariation How much the rhythm varies within the solo, 0-1.
 * @param chromaticPassing Chance of inserting chromatic passing tones, 0-1.
 */
@Serializable
data class SoloMarkovConfig(
    val intervalWeights: List<Float>,
    val restProbability: Float = 0.15f,
    val holdProbability: Float = 0.2f,
    val densityCurveMin: Float = 0.4f,
    val densityCurveMax: Float = 0.8f,
    val rhythmVariation: Float = 0.3f,
    val chromaticPassing: Float = 0.1f,
) {
    init {
        require(intervalWeights.size == 15) {
            "intervalWeights must have exactly 15 elements, got ${intervalWeights.size}"
        }
    }

    companion object {
        val MELODIC_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.02f, 0.03f, 0.05f, 0.08f, 0.10f, 0.15f, 0.25f, 0.10f, 0.25f, 0.15f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f),
        )

        val RHYTHMIC_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.01f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f, 0.10f, 0.40f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f),
            restProbability = 0.3f,
            holdProbability = 0.1f,
            densityCurveMin = 0.6f,
            densityCurveMax = 0.9f,
        )

        val EFFECT_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.05f, 0.06f, 0.07f, 0.08f, 0.07f, 0.06f, 0.05f, 0.12f, 0.05f, 0.06f, 0.07f, 0.08f, 0.07f, 0.06f, 0.05f),
            restProbability = 0.25f,
            holdProbability = 0.15f,
            chromaticPassing = 0.2f,
            densityCurveMin = 0.3f,
            densityCurveMax = 0.6f,
        )

        val WILD_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.064f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f),
            restProbability = 0.1f,
            densityCurveMin = 0.7f,
            densityCurveMax = 1.0f,
        )
    }
}

/**
 * Controls how a soloing track gravitates toward the vibe's lick pattern.
 * @param gravity Pull toward lick notes, 0-1. 0 = free improvisation, 1 = strict lick.
 * @param phraseLengthMin Minimum bars before deviating from the lick.
 * @param phraseLengthMax Maximum bars of lick-aligned playing before free improvisation.
 * @param reentryProbability Chance of snapping back to the lick after free playing, 0-1.
 */
@Serializable
data class LickBias(
    val gravity: Float = 0.5f,
    val phraseLengthMin: Int = 2,
    val phraseLengthMax: Int = 4,
    val reentryProbability: Float = 0.4f,
)

/**
 * Overrides for a track's melodic behavior during solos.
 * @param engine Switch to a different engine during solos (null = keep current).
 * @param lick Use a different lick during solos (null = keep vibe lick).
 * @param octaveShift Shift the note range up/down N octaves during solos.
 * @param noteRangeLow Override MIDI note floor during solos.
 * @param noteRangeHigh Override MIDI note ceiling during solos.
 */
@Serializable
data class MelodicOverride(
    val engine: Engine? = null,
    val lick: Lick? = null,
    val octaveShift: Int = 0,
    val noteRangeLow: Int? = null,
    val noteRangeHigh: Int? = null,
)

/**
 * How a track behaves when it's the active soloist.
 * Set on [TrackVoice.soloBehavior] to make a track solo-capable.
 * Tracks without this are never chosen for solos.
 *
 * @param volumeBoost Extra volume during solo, added to track volume. 0.2 = subtle lift.
 * @param densityBoost Extra density during solo. 0.3 = moderately busier.
 * @param timbreMin/timbreMax Timbre range during solo (wider = more expressive).
 * @param morphMin/morphMax Morph range during solo.
 * @param harmonicsMin/harmonicsMax Harmonics range during solo.
 * @param evolutionIntensity How much the tension system affects this track during solos, 0-1.
 * @param fillProbability Chance of playing fills at phrase boundaries during solos, 0-1.
 * @param melodicOverride Optional engine/range overrides during solos.
 * @param markovConfig Custom Markov config for this track's solo note generation.
 * @param lickBias How much the solo gravitates toward the vibe's lick pattern.
 */
@Serializable
data class SoloBehavior(
    val volumeBoost: Float = 0.2f,
    val densityBoost: Float = 0.3f,
    val timbreMin: Float = 0.2f,
    val timbreMax: Float = 0.8f,
    val morphMin: Float = 0.1f,
    val morphMax: Float = 0.7f,
    val harmonicsMin: Float = 0.2f,
    val harmonicsMax: Float = 0.8f,
    val evolutionIntensity: Float = 1.0f,
    val fillProbability: Float = 0.6f,
    val melodicOverride: MelodicOverride? = null,
    val markovConfig: SoloMarkovConfig? = null,
    val lickBias: LickBias? = null,
)

/**
 * How non-soloing tracks pull back during a solo. Set on [TrackVoice.duckingProfile].
 * Tracks without this use sensible defaults based on their [EnvelopeProfile].
 *
 * @param volumeReduction Volume drop during ducking, 0-1. 0.3 = subtle, 0.7 = dramatic.
 * @param densityReduction Density drop. 0.4 = noticeably sparser.
 * @param ghostReduction Ghost note reduction. 0.5 = half as many ghost notes.
 * @param fillSuppression How much to suppress fills. 0.8 = almost no fills while ducking.
 * @param simplify If true, simplify patterns to basic downbeats while ducking.
 * @param reverbBoost Extra reverb send while ducking (pushes backing tracks further back in the mix).
 */
@Serializable
data class DuckingProfile(
    val volumeReduction: Float = 0.3f,
    val densityReduction: Float = 0.4f,
    val ghostReduction: Float = 0.5f,
    val fillSuppression: Float = 0.8f,
    val simplify: Boolean = true,
    val reverbBoost: Float = 0.1f,
)


/**
 * A "band member" — a named group of tracks that solo together.
 * @param name Display name (e.g., "Rhythm Section", "Keys", "FX").
 * @param tracks Track indices this member controls (e.g., [0,1,2] for drums).
 * @param alwaysActive If true, this member never ducks (e.g., drums keep playing).
 * @param loudness Relative output level for this member, 0-1.
 * @param creativity How much this member varies from the base pattern, 0-1.
 * @param swing Timing swing amount for this member, 0-1.
 * @param drag Timing drag (behind the beat) for this member, 0-1.
 */
@Serializable
data class BandMember(
    val name: String,
    val tracks: List<Int>,
    val alwaysActive: Boolean = false,
    val loudness: Float = 0.5f,
    val creativity: Float = 0.5f,
    val swing: Float = 0.0f,
    val drag: Float = 0.0f,
)

/**
 * Build an NxN interaction matrix with labeled rows for readability.
 * Used for band member handoff and pull-in probabilities.
 *
 * ```
 * val handoff = bandMatrix(
 *     //          DRUM  BASS  KEYS  FX
 *     "Drummer" to row(0.00f, 0.35f, 0.35f, 0.10f),
 *     "Bassist" to row(0.25f, 0.00f, 0.40f, 0.15f),
 *     "Keys"    to row(0.20f, 0.35f, 0.00f, 0.20f),
 *     "FX"      to row(0.15f, 0.30f, 0.30f, 0.00f),
 * )
 * ```
 */
fun bandMatrix(vararg rows: Pair<String, List<Float>>): List<Float> {
    val n = rows.size
    val result = ArrayList<Float>(n * n)
    rows.forEach { (name, values) ->
        require(values.size == n) { "Row '$name' has ${values.size} values, expected $n" }
        result.addAll(values)
    }
    return result
}

fun row(vararg values: Float): List<Float> = values.toList()

/**
 * Build a 7x7 chord transition matrix with labeled rows.
 * Each row represents transition probabilities from one chord degree (I-VII)
 * to all others. Rows should sum to ~1.0.
 *
 * ```
 * val glacialDrift = chordMatrix(
 *     //    I     ii    iii   IV    V     vi    VII
 *     "I"   to row(0.70f, 0.05f, 0.02f, 0.05f, 0.03f, 0.05f, 0.10f),
 *     "ii"  to row(0.10f, 0.70f, 0.08f, 0.02f, 0.05f, 0.03f, 0.02f),
 *     ...
 * )
 * ```
 */
fun chordMatrix(vararg rows: Pair<String, List<Float>>): List<Float> {
    require(rows.size == 7) { "Chord matrix must have exactly 7 rows (I-VII), got ${rows.size}" }
    val result = ArrayList<Float>(49)
    rows.forEach { (name, values) ->
        require(values.size == 7) { "Row '$name' has ${values.size} values, expected 7" }
        result.addAll(values)
    }
    return result
}


/**
 * A reusable band definition — the cast of characters for a vibe.
 * Defines members, their track assignments, and interaction matrices.
 * Referenced by [Vibe.band], used by [SoloMode] sections in the arrangement.
 *
 * @param members The band members (named track groups with personality traits).
 * @param handoffMatrix NxN Markov matrix for lead handoff probabilities. Build with [bandMatrix].
 * @param pullInMatrix NxN matrix for pull-in probabilities. Build with [bandMatrix].
 * @param pullInBarsMin Minimum bars a pull-in lasts.
 * @param pullInBarsMax Maximum bars a pull-in lasts.
 * @param barsPerLeadMin Minimum bars each lead member plays before handoff.
 * @param barsPerLeadMax Maximum bars each lead member plays before handoff.
 */
@Serializable
data class Band(
    val members: List<BandMember>,
    val handoffMatrix: List<Float>,
    val pullInMatrix: List<Float>,
    val pullInBarsMin: Int = 2,
    val pullInBarsMax: Int = 4,
    val barsPerLeadMin: Int = 2,
    val barsPerLeadMax: Int = 6,
)

/**
 * Solo mode for a section — declares what kind of solo happens.
 * The band structure comes from [Vibe.band]; the section just picks the mode.
 *
 * - [LongFill]: Brief single-member spotlight on the lick. No handoff.
 * - [LickBuilder]: Aggressive lick mutation passed between members via handoff matrix.
 * - [Jam]: Free improv with configurable lick influence from prior mutations.
 */
@Serializable
sealed class SoloMode {
    @Serializable
    data class LongFill(
        val probability: Float = 0.5f,
        val barsMin: Int = 2,
        val barsMax: Int = 4,
    ) : SoloMode()

    @Serializable
    data class LickBuilder(
        val probability: Float = 0.7f,
        val mutationRate: Float = 0.5f,
    ) : SoloMode()

    @Serializable
    data class Jam(
        val probability: Float = 0.8f,
        val lickInfluence: Float = 0.5f,
    ) : SoloMode()
}


/**
 * A named rhythmic pattern that can be swapped in during a section.
 * Used in [MotifSet] for pattern variation within a section.
 *
 * @param name Display name (e.g., "driving", "halftime", "breakdown").
 * @param trackPatterns Per-track step patterns: map of track index to step velocities.
 *   Each list has one float per step (0.0 = rest, 0.0-1.0 = velocity).
 * @param swingOverride Override the genre's swing amount for this motif.
 * @param ghostProbability Override ghost note probability for this motif.
 * @param rhythmPattern Override rhythm pattern index for this motif.
 */
@Serializable
data class BeatMotif(
    val name: String,
    val trackPatterns: Map<Int, List<Float>> = emptyMap(),
    val swingOverride: Float? = null,
    val ghostProbability: Float? = null,
    val rhythmPattern: Int? = null,
)

/**
 * A collection of [BeatMotif]s that a section can switch between for variety.
 *
 * @param motifs Available beat patterns.
 * @param transitionWeights Markov matrix: probability of switching from motif i to motif j.
 * @param recencyDecay Penalty for recently-used motifs, 0-1. Higher = more variety.
 * @param barsPerMotifMin Minimum bars before switching motifs.
 * @param barsPerMotifMax Maximum bars before switching motifs.
 * @param switchProbability Chance of switching at a bar boundary, 0-1.
 */
@Serializable
data class MotifSet(
    val motifs: List<BeatMotif>,
    val transitionWeights: List<List<Float>> = emptyList(),
    val recencyDecay: Float = 0.5f,
    val barsPerMotifMin: Int = 4,
    val barsPerMotifMax: Int = 8,
    val switchProbability: Float = 0.6f,
)

/**
 * Per-track overrides within a specific section.
 * @param mutationRate Override pattern mutation rate for this track in this section.
 * @param motifLock If true, lock this track to its current motif (don't switch).
 * @param densityEnvelopeMin Minimum density envelope for this track in this section.
 * @param densityEnvelopeMax Maximum density envelope for this track in this section.
 */
@Serializable
data class TrackSectionOverride(
    val mutationRate: Float? = null,
    val motifLock: Boolean = false,
    val densityEnvelopeMin: Float? = null,
    val densityEnvelopeMax: Float? = null,
)

/**
 * One section in an [Arrangement] — a distinct musical phase like verse, chorus, or solo.
 *
 * @param name Display name (e.g., "groove", "verse", "chorus", "solo", "breakdown").
 * @param barsMin Minimum bars in this section before transition.
 * @param barsMax Maximum bars in this section.
 * @param barStep Increment within `[barsMin, barsMax]` when randomizing the section
 *   length. Default 1 = any value in the range. Set to 2 for "even bars only"
 *   (with `barsMin` even) or "odd bars only" (with `barsMin` odd) — useful for
 *   keeping phrase lengths musical (4-bar / 8-bar phrases). Set to e.g. 4 for
 *   4-bar increments only.
 * @param transitions Where this section can go next. List of (targetIndex, weight,
 *   transitionBars) edges. Empty = terminal section (arrangement ends here).
 *   Per-edge `transitionBars` controls macro pre-roll crossfade — see [SectionTransition].
 * @param recencyDecay Penalty for recently-visited sections in transition selection, 0-1.
 * @param macroOverrides Multiply macro values during this section.
 *   e.g., `MacroOverrides(energy = 1.4f)` boosts energy 40% during this section.
 * @param tensionOverride Replace the vibe's tension profile for this section.
 * @param soloMode Solo mode for this section (null = no solos).
 * @param motifSet Beat pattern variations for this section.
 * @param trackOverrides Per-track parameter overrides specific to this section.
 */
@Serializable
data class Section(
    val name: String,
    val barsMin: Int = 4,
    val barsMax: Int = 8,
    val barStep: Int = 1,
    val transitions: List<SectionTransition> = emptyList(),
    val recencyDecay: Float = 0.5f,
    val macroOverrides: MacroOverrides? = null,
    val tensionOverride: TensionProfile? = null,
    val soloMode: SoloMode? = null,
    val motifSet: MotifSet? = null,
    val trackOverrides: Map<Int, TrackSectionOverride>? = null,
    /** Override all CHORDAL tracks' comping style for this section. null = keep defaults. */
    val compingStyle: CompingStyle? = null,
    /** Override all CHORDAL tracks' section inversion for this section. null = keep defaults. */
    val compingInversion: SectionInversion? = null,
    /** Override CompingHumanization for ALL CHORDAL tracks in this section. null = keep track defaults. */
    val compingHumanization: CompingHumanization? = null,
    /** Override all melodic+chordal tracks' chord-follow mode. null = keep defaults. */
    val chordFollow: ChordFollow? = null,
    /** Per-section chord sequence. Null = inherit vibe's progression.
     *  When set, this section restarts the progression at its degree 0 on entry.
     *  Same constraints as [GenreProfile.customProgression]: size 1..8, degrees 0..6.
     *  Per-chord glide is honored when supplied. */
    val customProgression: List<ChordStep>? = null,
    /** Per-section chord-change rate override. Null = inherit vibe's chordsPerBar.
     *  Valid range 1..4; common values are 1 (static), 2 (standard), 4 (busy). */
    val chordsPerBar: Int? = null,
    /** Per-section tempo multiplier applied on top of the vibe's [Vibe.bpm].
     *  1.0 = no change (default), 0.5 = half-time breakdown, 2.0 = double-time burst.
     *  Useful range ~0.25..2.0. On each section transition the live BPM is
     *  scaled by (newMultiplier / previousMultiplier), so live tempo edits the
     *  user makes during a section carry through into subsequent sections at
     *  their relative multiplier. */
    val bpmMultiplier: Float = 1.0f,
) {
    init {
        customProgression?.let { validateProgression(it, "Section.customProgression") }
        chordsPerBar?.let {
            require(it in 1..4) { "Section.chordsPerBar must be 1..4, got $it" }
        }
        require(barStep in 1..16) { "Section.barStep must be 1..16, got $barStep" }
        require(bpmMultiplier > 0f) { "Section.bpmMultiplier must be > 0, got $bpmMultiplier" }
    }
}

/**
 * Section-based song structure. Sections transition between each other using
 * weighted Markov chains, creating organic song forms.
 *
 * Use the built-in presets to get started:
 * - `Arrangement.SIMPLE` — groove ↔ variation (2 sections)
 * - `Arrangement.WITH_SOLOS` — groove → solo → build (3 sections)
 * - `Arrangement.FULL` — intro → verse → chorus → solo → breakdown → outro (6 sections)
 * - `Arrangement.JAM` — groove ↔ improv with IMPROVISERS solos (2 sections, open-ended)
 *
 * @param sections Up to 8 sections.
 * @param introIndex Which section to start with (default 0; pass null for random weighted choice).
 * @param outroIndex Which section ends the arrangement (null = loops forever).
 * @param defaultSectionBars Default bar count if a section doesn't specify.
 */
@Serializable
data class Arrangement(
    val sections: List<Section>,
    val introIndex: Int? = 0,
    val outroIndex: Int? = null,
    val defaultSectionBars: Int = 8,
) {
    init {
        require(sections.size <= MAX_SECTIONS) {
            "Arrangement sections size ${sections.size} exceeds MAX_SECTIONS=$MAX_SECTIONS"
        }
    }

    companion object {
        const val MAX_SECTIONS = 8

        val SIMPLE = Arrangement(
            sections = listOf(
                Section(
                    name = "groove",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1.0f),
                    ),
                ),
                Section(
                    name = "variation",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 1.0f),
                    ),
                    macroOverrides = MacroOverrides(complexity = 1.5f),
                ),
            ),
        )

        val WITH_SOLOS = Arrangement(
            sections = listOf(
                Section(
                    name = "groove",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.3f),
                        SectionTransition(targetIndex = 2, weight = 0.7f),
                    ),
                ),
                Section(
                    name = "solo",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.8f),
                        SectionTransition(targetIndex = 2, weight = 0.2f),
                    ),
                    soloMode = SoloMode.LongFill(),
                ),
                Section(
                    name = "build",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 1.0f),
                    ),
                    macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.5f),
                ),
            ),
        )

        val FULL = Arrangement(
            sections = listOf(
                Section(
                    name = "intro",
                    barsMin = 4,
                    barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1.0f),
                    ),
                    macroOverrides = MacroOverrides(energy = 0.5f),
                ),
                Section(
                    name = "verse",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.7f),
                        SectionTransition(targetIndex = 3, weight = 0.2f),
                        SectionTransition(targetIndex = 4, weight = 0.1f),
                    ),
                ),
                Section(
                    name = "chorus",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.5f),
                        SectionTransition(targetIndex = 3, weight = 0.3f),
                        SectionTransition(targetIndex = 4, weight = 0.2f),
                    ),
                    macroOverrides = MacroOverrides(energy = 1.3f),
                ),
                Section(
                    name = "solo",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.6f),
                        SectionTransition(targetIndex = 1, weight = 0.3f),
                        SectionTransition(targetIndex = 4, weight = 0.1f),
                    ),
                    soloMode = SoloMode.LickBuilder(),
                    recencyDecay = 0.4f,
                ),
                Section(
                    name = "breakdown",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.8f),
                        SectionTransition(targetIndex = 1, weight = 0.2f),
                    ),
                    macroOverrides = MacroOverrides(energy = 0.4f, space = 1.6f),
                ),
                Section(
                    name = "outro",
                    barsMin = 8,
                    barsMax = 16,
                    transitions = emptyList(),
                    macroOverrides = MacroOverrides(energy = 0.3f, space = 1.5f),
                ),
            ),
            introIndex = 0,
            outroIndex = 5,
        )

        val JAM = Arrangement(
            introIndex = 0,  // always start in groove
            sections = listOf(
                Section(
                    name = "groove",
                    barsMin = 4,
                    barsMax = 12,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.6f),
                        SectionTransition(targetIndex = 1, weight = 0.4f),
                    ),
                    recencyDecay = 0.8f,
                    // Groove: tighter, more driving — slightly higher energy, lower complexity
                    macroOverrides = MacroOverrides(
                        energy = 1.2f,
                        complexity = 0.7f,
                        space = 0.8f,
                    ),
                ),
                Section(
                    name = "improv",
                    barsMin = 4,
                    barsMax = 12,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.6f),
                        SectionTransition(targetIndex = 1, weight = 0.4f),
                    ),
                    recencyDecay = 0.8f,
                    // Improv: open, exploratory — lower energy, higher complexity/space for mutation
                    macroOverrides = MacroOverrides(
                        energy = 0.6f,
                        complexity = 1.8f,
                        space = 1.5f,
                        mood = 1.3f,
                    ),
                    soloMode = SoloMode.Jam(probability = 0.7f),
                ),
            ),
        )
    }
}
