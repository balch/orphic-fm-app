package org.balch.orpheus.features.pulsar

import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.PickerConfig
import org.balch.orpheus.ui.widgets.PickerEntry

// Full engine ring using C++ Plaits engine indices (0-23).
// Same 17 engines as the drum picker but with raw Plaits indices
// so values can be sent directly to the C++ Pulsar engine.
private val PULSAR_FULL_RING = listOf(
    PickerEntry("BD",  21, OrpheusColors.neonMagenta),
    PickerEntry("SD",  22, OrpheusColors.electricBlue),
    PickerEntry("HH",  23, OrpheusColors.synthGreen),
    PickerEntry("FM",  10, OrpheusColors.warmGlow),
    PickerEntry("NSE", 17, OrpheusColors.neonCyan),
    PickerEntry("WSH",  9, OrpheusColors.enginePurple),
    PickerEntry("VA",   8, OrpheusColors.engineRed),
    PickerEntry("ADD", 12, OrpheusColors.engineBlue),
    PickerEntry("GRN", 11, OrpheusColors.engineGreen),
    PickerEntry("STR", 19, OrpheusColors.engineYellow),
    PickerEntry("MOD", 20, OrpheusColors.engineOrange),
    PickerEntry("PAR", 18, OrpheusColors.neonMagenta.copy(alpha = 0.8f)),
    PickerEntry("SWM", 16, OrpheusColors.electricBlue.copy(alpha = 0.8f)),
    PickerEntry("CHD", 14, OrpheusColors.synthGreen.copy(alpha = 0.8f)),
    PickerEntry("WTB", 13, OrpheusColors.presetOrange),
    PickerEntry("SPK", 15, OrpheusColors.warmGlow.copy(alpha = 0.9f)),
    PickerEntry("SM",   6, OrpheusColors.engineOrange.copy(alpha = 0.8f)),
)

// V1.2 engines (C++ only) — double-click easter egg ring, using C++ indices.
// Also includes 4 Braids character engines (ids 105-108).
private val PULSAR_V2_RING = listOf(
    PickerEntry("VCF",  0, OrpheusColors.engineRed),       // VirtualAnalogVCF
    PickerEntry("PD",   1, OrpheusColors.enginePurple),    // PhaseDistortion
    PickerEntry("DX",   2, OrpheusColors.warmGlow),        // SixOp FM bank 1
    PickerEntry("DX2",  3, OrpheusColors.warmGlow.copy(alpha = 0.85f)),  // SixOp FM bank 2
    PickerEntry("DX3",  4, OrpheusColors.warmGlow.copy(alpha = 0.7f)),   // SixOp FM bank 3
    PickerEntry("TRN",  5, OrpheusColors.engineGreen),     // WaveTerrain
    PickerEntry("ENS",  6, OrpheusColors.engineBlue),      // StringMachine
    PickerEntry("NES",  7, OrpheusColors.neonCyan),        // Chiptune
    // Braids character engines (ids 105..108)
    PickerEntry("CSAW", 105, OrpheusColors.engineYellow),
    PickerEntry("TOY",  106, OrpheusColors.presetOrange),
    PickerEntry("VOW",  107, OrpheusColors.synthGreen),
    PickerEntry("?",    108, OrpheusColors.neonMagenta),
)

val PULSAR_V2_PICKER = PickerConfig(PULSAR_V2_RING, "V2", -1)

// Braids chord engines (triple-click ring, ids 100..104).
private val PULSAR_V3_RING = listOf(
    PickerEntry("3SAW", 100, OrpheusColors.engineRed),
    PickerEntry("3SQR", 101, OrpheusColors.enginePurple),
    PickerEntry("3TRI", 102, OrpheusColors.engineYellow),
    PickerEntry("3SIN", 103, OrpheusColors.engineBlue),
    PickerEntry("3RM",  104, OrpheusColors.synthGreen),
)

val PULSAR_V3_PICKER = PickerConfig(PULSAR_V3_RING, "CHD", -1)

/** Kick track: full ring, BD center */
val PULSAR_KICK_PICKER = PickerConfig(PULSAR_FULL_RING, "BD", 21)

/** Perc track: full ring, SD center */
val PULSAR_PERC_PICKER = PickerConfig(PULSAR_FULL_RING, "SD", 22)

/** Bass track: full ring, WSH center */
val PULSAR_BASS_PICKER = PickerConfig(PULSAR_FULL_RING, "WSH", 9)

/** Keys track: full ring, SM center */
val PULSAR_KEYS_PICKER = PickerConfig(PULSAR_FULL_RING, "SM", 6)

/** HiHat track: full ring, HH center */
val PULSAR_HIHAT_PICKER = PickerConfig(PULSAR_FULL_RING, "HH", 23)

/** Pad track: full ring, STR center */
val PULSAR_PAD_PICKER = PickerConfig(PULSAR_FULL_RING, "STR", 19)

/** Texture track: full ring, GRN center */
val PULSAR_TEXTURE_PICKER = PickerConfig(PULSAR_FULL_RING, "GRN", 11)

/** FX track: full ring, MOD center */
val PULSAR_FX_PICKER = PickerConfig(PULSAR_FULL_RING, "MOD", 20)

/** Indexed access by track number (0-7) */
val PULSAR_TRACK_PICKERS = listOf(
    PULSAR_KICK_PICKER, PULSAR_PERC_PICKER, PULSAR_HIHAT_PICKER,
    PULSAR_BASS_PICKER, PULSAR_KEYS_PICKER,
    PULSAR_PAD_PICKER, PULSAR_TEXTURE_PICKER, PULSAR_FX_PICKER,
)

/** Track display names */
val PULSAR_TRACK_NAMES = listOf("KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXTURE", "FX")

/** Engine index to short label for display */
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
    else -> "E$engineIndex"
}
