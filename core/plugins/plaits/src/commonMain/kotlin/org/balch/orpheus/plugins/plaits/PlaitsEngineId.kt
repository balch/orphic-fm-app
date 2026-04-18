package org.balch.orpheus.plugins.plaits

/**
 * Identifies each available synthesis engine.
 * Engines are added incrementally as they are ported from Plaits.
 */
enum class PlaitsEngineId(val displayName: String) {
    // Wrapped existing drum engines
    ANALOG_BASS_DRUM("808 Bass Drum"),
    ANALOG_SNARE_DRUM("808 Snare Drum"),
    METALLIC_HI_HAT("808 Hi-Hat"),
    FM_DRUM("FM Drum"),
    // Pitched synthesis engines
    FM("FM Synthesis"),
    NOISE("Filtered Noise"),
    WAVESHAPING("Waveshaping"),
    VIRTUAL_ANALOG("Virtual Analog"),
    ADDITIVE("Additive"),
    GRAIN("Grain"),
    STRING("String"),
    MODAL("Modal"),
    PARTICLE("Particle"),
    SWARM("Swarm"),
    CHORD("Chord"),
    WAVETABLE("Wavetable"),
    SPEECH("Speech"),
    // V1.2 engines (C++ only — no Kotlin DSP)
    VIRTUAL_ANALOG_VCF("Virtual Analog VCF"),
    PHASE_DISTORTION("Phase Distortion"),
    SIX_OP_FM("Six-Op FM"),
    WAVE_TERRAIN("Wave Terrain"),
    STRING_MACHINE("String Machine"),
    CHIPTUNE("Chiptune"),
    SIX_OP_FM_2("Six-Op FM Bank 2"),
    SIX_OP_FM_3("Six-Op FM Bank 3"),
;

/** True for v1.2 engines that only render in C++ (no Kotlin DSP). */
val isNativeOnly: Boolean get() = ordinal >= 17
}
