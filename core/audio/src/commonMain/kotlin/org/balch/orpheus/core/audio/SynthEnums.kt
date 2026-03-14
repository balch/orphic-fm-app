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
    SYNTH("Synth"),       // 0
    DRUMS("Drums"),       // 1
    REPL("REPL"),         // 2
    LFO("LFO"),           // 3
    RESONATOR("Cowbell"), // 4
    WARPS("Feedback"),    // 5
    FLUX("Warbles"),      // 6
    BENDER("Bender"),     // 7
    STRINGS("Strings")    // 8
}
