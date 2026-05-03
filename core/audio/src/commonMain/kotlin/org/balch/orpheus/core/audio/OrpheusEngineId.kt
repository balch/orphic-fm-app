package org.balch.orpheus.core.audio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Single source of truth for synthesis engine selection.
 * The numeric [id] IS the C++ engine index — same value that
 * voice_params[].engine_index stores in C++ today.
 *
 * ID ranges:
 * - -1      : OSC (built-in triangle+square fallback) — kept negative
 *             because v1.2 already occupies positive cpp_idx 0
 * - 0..7    : Plaits v1.2 (engine2) — native-only
 * - 8..23   : Plaits v1 (engine1) — sparse; matches existing kEngineMap[] values
 * - 100..108: Braids
 */
@Serializable
enum class OrpheusEngineId(val id: Int, val displayName: String) {
    // Declaration order is stable — do not reorder without migrating preset/save data.

    // Drums (ordinals 0..3)
    @SerialName("BD")  ANALOG_BASS_DRUM(21, "808 Bass Drum"),
    @SerialName("SD")  ANALOG_SNARE_DRUM(22, "808 Snare Drum"),
    @SerialName("HH")  METALLIC_HI_HAT(23, "808 Hi-Hat"),
    @SerialName("FM_DRUM") FM_DRUM(21, "FM Drum"),  // aliases ANALOG_BASS_DRUM on C++ side

    // Plaits v1 pitched (ordinals 4..16)
    @SerialName("FM")  FM(10, "FM Synthesis"),
    @SerialName("NSE") NOISE(17, "Filtered Noise"),
    @SerialName("WSH") WAVESHAPING(9, "Waveshaping"),
    @SerialName("VA")  VIRTUAL_ANALOG(8, "Virtual Analog"),
    @SerialName("ADD") ADDITIVE(12, "Additive"),
    @SerialName("GRN") GRAIN(11, "Grain"),
    @SerialName("STR") STRING(19, "String"),
    @SerialName("MOD") MODAL(20, "Modal"),
    @SerialName("PAR") PARTICLE(18, "Particle"),
    @SerialName("SWM") SWARM(16, "Swarm"),
    @SerialName("CHD") CHORD(14, "Chord"),
    @SerialName("WTB") WAVETABLE(13, "Wavetable"),
    @SerialName("SPK") SPEECH(15, "Speech"),

    // Plaits v1.2 native-only (ordinals 17..24)
    @SerialName("VCF") VIRTUAL_ANALOG_VCF(0, "Virtual Analog VCF"),
    @SerialName("PD")  PHASE_DISTORTION(1, "Phase Distortion"),
    @SerialName("DX")  SIX_OP_FM(2, "Six-Op FM"),
    @SerialName("TRN") WAVE_TERRAIN(5, "Wave Terrain"),
    @SerialName("ENS") STRING_MACHINE(6, "String Machine"),
    @SerialName("NES") CHIPTUNE(7, "Chiptune"),
    @SerialName("DX2") SIX_OP_FM_2(3, "Six-Op FM Bank 2"),
    @SerialName("DX3") SIX_OP_FM_3(4, "Six-Op FM Bank 3"),

    // ── Braids — chord engines (ids 100..104) ──
    @SerialName("BRAIDS_TRIPLE_SAW")        BRAIDS_TRIPLE_SAW(100, "Triple Saw"),
    @SerialName("BRAIDS_TRIPLE_SQUARE")     BRAIDS_TRIPLE_SQUARE(101, "Triple Square"),
    @SerialName("BRAIDS_TRIPLE_TRIANGLE")   BRAIDS_TRIPLE_TRIANGLE(102, "Triple Triangle"),
    @SerialName("BRAIDS_TRIPLE_SINE")       BRAIDS_TRIPLE_SINE(103, "Triple Sine"),
    @SerialName("BRAIDS_TRIPLE_RING_MOD")   BRAIDS_TRIPLE_RING_MOD(104, "Triple Ring Mod"),

    // ── Braids — character engines (ids 105..108) ──
    @SerialName("BRAIDS_CSAW")              BRAIDS_CSAW(105, "CSAW"),
    @SerialName("BRAIDS_TOY")               BRAIDS_TOY(106, "Toy"),
    @SerialName("BRAIDS_VOWEL_FOF")         BRAIDS_VOWEL_FOF(107, "Vowel FOF"),
    @SerialName("BRAIDS_QUESTION_MARK")     BRAIDS_QUESTION_MARK(108, "Question Mark"),

    // OSC must come LAST so it doesn't shift any prior ordinal.
    // id=-1 is the sentinel for OSC mode — C++ voice routing checks engine_index < 0.
    @SerialName("OSC") OSC(-1, "OSC"),
    ;

    /** True for engines with no Kotlin DSP fallback (v1.2 + future Braids). */
    val isNativeOnly: Boolean
        get() = this in NATIVE_ONLY

    /** True for the Braids triple-oscillator chord shapes. */
    val isChordEngine: Boolean
        get() = this in CHORD_ENGINES

    companion object {
        private val NATIVE_ONLY = setOf(
            VIRTUAL_ANALOG_VCF, PHASE_DISTORTION, SIX_OP_FM,
            WAVE_TERRAIN, STRING_MACHINE, CHIPTUNE,
            SIX_OP_FM_2, SIX_OP_FM_3,
            BRAIDS_TRIPLE_SAW, BRAIDS_TRIPLE_SQUARE, BRAIDS_TRIPLE_TRIANGLE,
            BRAIDS_TRIPLE_SINE, BRAIDS_TRIPLE_RING_MOD,
            BRAIDS_CSAW, BRAIDS_TOY, BRAIDS_VOWEL_FOF, BRAIDS_QUESTION_MARK,
        )

        private val CHORD_ENGINES = setOf(
            BRAIDS_TRIPLE_SAW, BRAIDS_TRIPLE_SQUARE, BRAIDS_TRIPLE_TRIANGLE,
            BRAIDS_TRIPLE_SINE, BRAIDS_TRIPLE_RING_MOD,
        )

        /**
         * Returns the first entry whose [id] equals [id], or null if none match.
         * Note: [FM_DRUM] (id = 21) aliases [ANALOG_BASS_DRUM] and is not reachable
         * via this lookup — [ANALOG_BASS_DRUM] is returned for id 21. The two
         * resolve to the same C++ engine, so audio behavior is identical, but
         * round-trip identity from id is lost.
         */
        fun fromId(id: Int): OrpheusEngineId? = entries.firstOrNull { it.id == id }
    }
}
