package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.anonmalies.Anomaly
import org.balch.orpheus.features.pulsar.anonmalies.CrossfadeAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.CutAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.FilterAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.ScratchAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.SwellAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.TapeAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly

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
 * @param stepCount Steps per pattern. 16 = standard, 32 = double-length phrases,
 *   64 = quad-length phrases (max).
 * @param tension Build-and-release arc configuration.
 * @param arrangement Optional section-based structure (verse, chorus, solo, etc.)
 * @param effects Delay and reverb tuning for this vibe.
 * @param lickWah Optional per-track lick-wah voice. Tracks whose [TrackRole.Melodic.wahLick] is
 *   set filter their rendered audio through this tempo-synced bandpass wah before it accumulates
 *   into the mix — a standing timbral insert, independent of the [WahAnomaly]. Null = no track
 *   filters (the insert stays inert even if a track opts in).
 * @param anomalies Rare dramatic events (see [Anomaly]) the Anomaly Engine may fire — e.g. a
 *   [VoidAnomaly] drop-to-silence or a [LickAnomaly] original-riff swap. Empty = none; the vibe
 *   then ignores the manual anomaly trigger. At most one anomaly of each concrete type.
 */
@Serializable
data class Vibe(
    val name: String,
    val album: Album = Album.STEALTH,
    val tracks: List<TrackVoice>,
    val lick: Lick? = null,
    val lickMutation: Float = 0.5f,
    val lickOctave: Int = -1,
    val lickRotation: LickRotation? = null,
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
    val lickWah: WahParams? = null,
    val anomalies: List<Anomaly> = emptyList(),
) {
    init {
        require(tracks.size == 8) {
            "Vibe requires exactly 8 tracks, got ${tracks.size}"
        }
        // The Anomaly Engine only arms while a section graph is active — a declared anomaly on
        // an arrangement-less vibe would flash the manual-trigger tint but never fire.
        require(anomalies.isEmpty() || arrangement != null) {
            "anomalies require an arrangement — the Anomaly Engine arms at section boundaries"
        }
        // C++ has a single void config bank and a single lick-anomaly slot, so each concrete
        // anomaly type may appear at most once.
        require(anomalies.filterIsInstance<VoidAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one VoidAnomaly"
        }
        require(anomalies.filterIsInstance<WahAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one WahAnomaly"
        }
        require(anomalies.filterIsInstance<CrossfadeAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one CrossfadeAnomaly"
        }
        require(anomalies.filterIsInstance<CutAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one CutAnomaly"
        }
        require(anomalies.filterIsInstance<SwellAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one SwellAnomaly"
        }
        require(anomalies.filterIsInstance<TapeAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one TapeAnomaly"
        }
        require(anomalies.filterIsInstance<ScratchAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one ScratchAnomaly"
        }
        require(anomalies.filterIsInstance<FilterAnomaly>().size <= 1) {
            "Vibe.anomalies may contain at most one FilterAnomaly"
        }
        val lickAnomalies = anomalies.filterIsInstance<LickAnomaly>()
        require(lickAnomalies.size <= 1) {
            "Vibe.anomalies may contain at most one LickAnomaly"
        }
        // A LickAnomaly rides the lick bank, so the vibe must supply a lick source to ride over.
        require(lickAnomalies.isEmpty() || lickRotation != null || lick != null) {
            "Vibe.anomalies has a LickAnomaly but the vibe has no lick source (set lick or lickRotation)"
        }
        // pool + the lick-anomaly slot share the C++ lick bank; together they must fit it.
        // NB: this formula is NOT the pushed bank size — with lickRotation == null, a LickAnomaly
        // pushes an implicit [lick, anomalyLick] 2-slot bank (counted as 1 here), undercounting by
        // design and still safely under MAX_LICK_POOL.
        require((lickRotation?.pool?.size ?: 0) + lickAnomalies.size <= LickRotation.MAX_LICK_POOL) {
            "lick bank (rotation pool ${lickRotation?.pool?.size ?: 0} + ${lickAnomalies.size} lick " +
                "anomaly) exceeds MAX_LICK_POOL=${LickRotation.MAX_LICK_POOL}"
        }
    }
}
