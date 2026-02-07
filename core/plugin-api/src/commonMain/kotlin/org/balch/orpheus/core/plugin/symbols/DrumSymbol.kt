package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val DRUM_URI = "org.balch.orpheus.plugins.drum"

enum class DrumSymbol(
    override val symbol: Symbol,
    override val uri: String = DRUM_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MIX("mix", displayName = "Mix"),
    BD_FREQ("bd_freq", displayName = "BD Frequency"),
    BD_TONE("bd_tone", displayName = "BD Tone"),
    BD_DECAY("bd_decay", displayName = "BD Decay"),
    BD_P4("bd_p4", displayName = "BD P4"),
    BD_P5("bd_p5", displayName = "BD P5"),
    SD_FREQ("sd_freq", displayName = "SD Frequency"),
    SD_TONE("sd_tone", displayName = "SD Tone"),
    SD_DECAY("sd_decay", displayName = "SD Decay"),
    SD_P4("sd_p4", displayName = "SD P4"),
    HH_FREQ("hh_freq", displayName = "HH Frequency"),
    HH_TONE("hh_tone", displayName = "HH Tone"),
    HH_DECAY("hh_decay", displayName = "HH Decay"),
    HH_P4("hh_p4", displayName = "HH P4"),
    BD_TRIGGER_SRC("bd_trigger_src", displayName = "BD Trigger Source"),
    BD_PITCH_SRC("bd_pitch_src", displayName = "BD Pitch Source"),
    SD_TRIGGER_SRC("sd_trigger_src", displayName = "SD Trigger Source"),
    SD_PITCH_SRC("sd_pitch_src", displayName = "SD Pitch Source"),
    HH_TRIGGER_SRC("hh_trigger_src", displayName = "HH Trigger Source"),
    HH_PITCH_SRC("hh_pitch_src", displayName = "HH Pitch Source"),
    BYPASS("bypass", displayName = "Bypass"),
    BD_ENGINE("bd_engine", displayName = "BD Engine"),
    SD_ENGINE("sd_engine", displayName = "SD Engine"),
    HH_ENGINE("hh_engine", displayName = "HH Engine")
}
