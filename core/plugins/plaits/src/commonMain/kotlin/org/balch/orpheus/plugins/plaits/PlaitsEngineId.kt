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
}
