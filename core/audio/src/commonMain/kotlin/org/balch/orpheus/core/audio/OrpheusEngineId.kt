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
 * - 200..204: Chaos / fractal (Lorenz/Rössler/Duffing/Hénon/Chua) — native-only
 */
@Serializable
enum class OrpheusEngineId(val id: Int, val displayName: String) {
    // Declaration order is stable — do not reorder without migrating preset/save data.

    // Drums (ordinals 0..3)
    @SerialName("BD")  BD(21, "808 Bass Drum"),
    @SerialName("SD")  SD(22, "808 Snare Drum"),
    @SerialName("HH")  HH(23, "808 Hi-Hat"),
    @SerialName("FM_DRUM") FM_DRUM(21, "FM Drum"),  // aliases BD on C++ side

    // Plaits v1 pitched (ordinals 4..16)
    @SerialName("FM")  FM(10, "FM Synthesis"),
    @SerialName("NSE") NSE(17, "Filtered Noise"),
    @SerialName("WSH") WSH(9, "Waveshaping"),
    @SerialName("VA")  VA(8, "Virtual Analog"),
    @SerialName("ADD") ADD(12, "Additive"),
    @SerialName("GRN") GRN(11, "Grain"),
    @SerialName("STR") STR(19, "String"),
    @SerialName("MOD") MOD(20, "Modal"),
    @SerialName("PAR") PAR(18, "Particle"),
    @SerialName("SWM") SWM(16, "Swarm"),
    @SerialName("CHD") CHD(14, "Chord"),
    @SerialName("WTB") WTB(13, "Wavetable"),
    @SerialName("SPK") SPK(15, "Speech"),

    // Plaits v1.2 native-only (ordinals 17..24)
    @SerialName("VCF") VCF(0, "Virtual Analog VCF"),
    @SerialName("PD")  PD(1, "Phase Distortion"),
    @SerialName("DX")  DX(2, "Six-Op FM"),
    @SerialName("TRN") TRN(5, "Wave Terrain"),
    @SerialName("ENS") ENS(6, "String Machine"),
    @SerialName("NES") NES(7, "Chiptune"),
    @SerialName("DX2") DX2(3, "Six-Op FM Bank 2"),
    @SerialName("DX3") DX3(4, "Six-Op FM Bank 3"),

    // ── Braids — chord engines (ids 100..104) ──
    @SerialName("TRIPLE_SAW")        TRIPLE_SAW(100, "Triple Saw"),
    @SerialName("TRIPLE_SQUARE")     TRIPLE_SQUARE(101, "Triple Square"),
    @SerialName("TRIPLE_TRIANGLE")   TRIPLE_TRIANGLE(102, "Triple Triangle"),
    @SerialName("TRIPLE_SINE")       TRIPLE_SINE(103, "Triple Sine"),
    @SerialName("TRIPLE_RING_MOD")   TRIPLE_RING_MOD(104, "Triple Ring Mod"),

    // ── Braids — character engines (ids 105..108) ──
    @SerialName("CSAW")              CSAW(105, "CSAW"),
    @SerialName("TOY")               TOY(106, "Toy"),
    @SerialName("VOWEL_FOF")         VOWEL_FOF(107, "Vowel FOF"),
    @SerialName("QUESTION_MARK")     QUESTION_MARK(108, "Question Mark"),

    // ── Chaos / fractal engines (ids 200..204) ──
    @SerialName("LRZ") LRZ(200, "Chaos · Lorenz"),
    @SerialName("ROS") ROS(201, "Chaos · Rössler"),
    @SerialName("DUF") DUF(202, "Chaos · Duffing"),
    @SerialName("HEN") HEN(203, "Chaos · Hénon"),
    @SerialName("CHU") CHU(204, "Chaos · Chua"),

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

    /**
     * Engine-enforced pinning of `harmonics`. Returns true for engines whose
     * harmonics parameter is a quantized 32-step patch selector — values cannot
     * interpolate meaningfully, so the vibe-set value must always be honored
     * verbatim regardless of `OrpheusEngine.pinHarmonics`.
     *
     * Currently the DX-family engines (DX, DX2, DX3 — 6-op FM with sysex banks).
     */
    val forcePinHarmonics: Boolean
        get() = this in DX_PATCH_ENGINES

    companion object {
        private val NATIVE_ONLY = setOf(
            VCF, PD, DX,
            TRN, ENS, NES,
            DX2, DX3,
            TRIPLE_SAW, TRIPLE_SQUARE, TRIPLE_TRIANGLE,
            TRIPLE_SINE, TRIPLE_RING_MOD,
            CSAW, TOY, VOWEL_FOF, QUESTION_MARK,
            LRZ, ROS, DUF, HEN, CHU,
        )

        private val CHORD_ENGINES = setOf(
            TRIPLE_SAW, TRIPLE_SQUARE, TRIPLE_TRIANGLE,
            TRIPLE_SINE, TRIPLE_RING_MOD,
        )

        private val DX_PATCH_ENGINES = setOf(DX, DX2, DX3)

        /**
         * Returns the first entry whose [id] equals [id], or null if none match.
         * Note: [FM_DRUM] (id = 21) aliases [BD] and is not reachable
         * via this lookup — [BD] is returned for id 21. The two
         * resolve to the same C++ engine, so audio behavior is identical, but
         * round-trip identity from id is lost.
         */
        fun fromId(id: Int): OrpheusEngineId? = entries.firstOrNull { it.id == id }
    }
}
