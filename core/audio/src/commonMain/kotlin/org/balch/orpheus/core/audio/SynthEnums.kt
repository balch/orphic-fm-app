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
    STRINGS("Strings"),   // 8
    BASS("Bass"),          // 9
    TIDES_1("Waves 1"),   // 10
    TIDES_2("Waves 2"),   // 11
    TIDES_3("Waves 3"),   // 12
    TIDES_4("Waves 4")    // 13
}
