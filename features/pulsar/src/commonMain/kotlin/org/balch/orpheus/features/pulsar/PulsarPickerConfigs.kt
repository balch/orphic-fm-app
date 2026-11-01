package org.balch.orpheus.features.pulsar

/** Track display names */
val PULSAR_TRACK_NAMES = listOf("KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXTURE", "FX")

/** [PULSAR_TRACK_NAMES] short enough for a mixer strip, where TEXTURE would ellipsize. */
val PULSAR_TRACK_STRIP_NAMES = listOf("KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXT", "FX")

/** The short code the Pulsar pills use for a bare engine, by C++ id (the Braids codes read "3SAW", not "TRIPLE_SAW"). */
fun pulsarEngineLabel(engineIndex: Int): String = when (engineIndex) {
    0 -> "VCF"; 1 -> "PD"; 2 -> "DX"; 3 -> "DX2"; 4 -> "DX3"; 5 -> "TRN"
    6 -> "ENS"; 7 -> "NES"; 8 -> "VA"; 9 -> "WSH"; 10 -> "FM"; 11 -> "GRN"
    12 -> "ADD"; 13 -> "WTB"; 14 -> "CHD"; 15 -> "SPK"; 16 -> "SWM"
    17 -> "NSE"; 18 -> "PAR"; 19 -> "STR"; 20 -> "MOD"
    21 -> "BD"; 22 -> "SD"; 23 -> "HH"
    // Braids chord engines (100..104)
    100 -> "3SAW"; 101 -> "3SQR"; 102 -> "3TRI"; 103 -> "3SIN"; 104 -> "3RM"
    // Braids character engines (105..108)
    105 -> "CSAW"; 106 -> "TOY"; 107 -> "VOW"; 108 -> "?"
    // Chaos / fractal engines (200..204)
    200 -> "LRZ"; 201 -> "ROS"; 202 -> "DUF"; 203 -> "HEN"; 204 -> "CHU"
    300 -> "MST"; -1 -> "OSC"
    else -> "E$engineIndex"
}
