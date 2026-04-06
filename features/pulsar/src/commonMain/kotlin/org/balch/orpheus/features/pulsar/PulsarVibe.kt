package org.balch.orpheus.features.pulsar

import kotlinx.serialization.Serializable

@Serializable
data class LickStep(
    val scaleDegree: Int,
    val duration: Float,
    val velocity: Float = 0.8f,
)

@Serializable
data class Lick(
    val steps: List<LickStep>,
    val loopLength: Int = steps.size,
) {
    init {
        require(steps.size <= MAX_LICK_STEPS) {
            "Lick steps size ${steps.size} exceeds MAX_LICK_STEPS=$MAX_LICK_STEPS"
        }
    }

    companion object {
        const val MAX_LICK_STEPS = 32
    }
}

@Serializable
data class MacroTarget(
    val min: Float,
    val max: Float,
)

@Serializable
data class TrackMacroMap(
    val energyVolume: MacroTarget,
    val energyDensity: MacroTarget,
    val complexitySwing: MacroTarget,
    val complexityVariation: MacroTarget,
    val spaceDecay: MacroTarget,
    val spaceReverbSend: MacroTarget,
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
            spaceReverbSend = MacroTarget(0.0f, 0.1f),
            moodHarmonics = MacroTarget(0.3f, 0.6f),
            moodTimbre = MacroTarget(0.2f, 0.5f),
        )

        val MELODIC = TrackMacroMap(
            energyVolume = MacroTarget(0.5f, 1.0f),
            energyDensity = MacroTarget(0.4f, 0.9f),
            complexitySwing = MacroTarget(0.0f, 0.15f),
            complexityVariation = MacroTarget(0.0f, 0.3f),
            spaceDecay = MacroTarget(0.2f, 0.5f),
            spaceReverbSend = MacroTarget(0.0f, 0.1f),
            moodHarmonics = MacroTarget(0.3f, 0.7f),
            moodTimbre = MacroTarget(0.4f, 0.8f),
        )

        val EFFECT = TrackMacroMap(
            energyVolume = MacroTarget(0.2f, 0.5f),
            energyDensity = MacroTarget(0.05f, 0.25f),
            complexitySwing = MacroTarget(0.0f, 0.2f),
            complexityVariation = MacroTarget(0.0f, 0.25f),
            spaceDecay = MacroTarget(0.5f, 0.9f),
            spaceReverbSend = MacroTarget(0.2f, 0.55f),
            moodHarmonics = MacroTarget(0.3f, 0.7f),
            moodTimbre = MacroTarget(0.4f, 0.8f),
        )

        val WILD = TrackMacroMap(
            energyVolume = MacroTarget(0.1f, 0.4f),
            energyDensity = MacroTarget(0.05f, 0.3f),
            complexitySwing = MacroTarget(0.0f, 0.5f),
            complexityVariation = MacroTarget(0.1f, 0.6f),
            spaceDecay = MacroTarget(0.4f, 0.8f),
            spaceReverbSend = MacroTarget(0.3f, 0.7f),
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

@Serializable
enum class EnvelopeProfile(val id: Int) {
    RHYTHM(0),
    MELODIC(1),
    EFFECT(2),
    WILD(3),
}

@Serializable
data class TrackVoice(
    val engineEdm: Engine,
    val engineSpace: Engine,
    val isPercussive: Boolean,
    val volume: Float = 0.8f,
    val pan: Float = 0.0f,
    val harmonics: Float = 0.5f,
    val timbre: Float = 0.5f,
    val morph: Float = 0.3f,
    val envelopeProfile: EnvelopeProfile = EnvelopeProfile.RHYTHM,
    val macroMap: TrackMacroMap = TrackMacroMap.RHYTHM,
)

@Serializable
data class GenreProfile(
    val baseDensity: FloatArray,
    val swingAmount: Float,
    val ghostProbability: Float,
    val noteRangeLow: Int,
    val noteRangeHigh: Int,
    val rhythmPattern: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as GenreProfile
        return baseDensity.contentEquals(other.baseDensity) &&
            swingAmount == other.swingAmount &&
            ghostProbability == other.ghostProbability &&
            noteRangeLow == other.noteRangeLow &&
            noteRangeHigh == other.noteRangeHigh &&
            rhythmPattern == other.rhythmPattern
    }

    override fun hashCode(): Int {
        var result = baseDensity.contentHashCode()
        result = 31 * result + swingAmount.hashCode()
        result = 31 * result + ghostProbability.hashCode()
        result = 31 * result + noteRangeLow
        result = 31 * result + noteRangeHigh
        result = 31 * result + rhythmPattern
        return result
    }
}

@Serializable
data class Vibe(
    val name: String,
    val tracks: List<TrackVoice>,
    val lick: Lick? = null,
    val lickMutation: Float = 0.5f,
    val seed: Int = 0,
    val bpm: Float,
    val envelopeMode: Int = 0,  // 0=AD, 1=Tides, 2=Blend
    val rootNote: Int,
    val scaleIndex: Int,
    val genre: GenreProfile,
    val energy: Float = 0.5f,
    val complexity: Float = 0.3f,
    val space: Float = 0.4f,
    val mood: Float = 0.5f,
) {
    init {
        require(tracks.size == 8) {
            "Vibe requires exactly 8 tracks, got ${tracks.size}"
        }
    }
}
