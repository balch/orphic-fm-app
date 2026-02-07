package org.balch.orpheus.core.audio

/**
 * Modes for the Hyper LFO (X-Y modulation or boolean logic).
 */
enum class HyperLfoMode {
    AND,
    OFF,
    OR
}

/**
 * Audio sources available for Warps carrier and modulator inputs.
 */
enum class WarpsSource(val displayName: String) {
    SYNTH("Synth"),
    DRUMS("Drums"),
    REPL("REPL"),
    LFO("LFO"),
    RESONATOR("Cowbell"),
    WARPS("Feedback"),
    FLUX("Warbles")
}
