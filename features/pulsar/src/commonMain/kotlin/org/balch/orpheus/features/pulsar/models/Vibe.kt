package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

enum class Album(val title: String) {
    STEALTH("Stealth"),
    RIF("RIF"),
    ZERO_TO_ONE("0-2-1"),
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
    BLUES(13),
    BLUES_PENTATONIC(14),
    BLUES_MAJOR(15),
}

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
    val album: Album = Album.STEALTH,
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
