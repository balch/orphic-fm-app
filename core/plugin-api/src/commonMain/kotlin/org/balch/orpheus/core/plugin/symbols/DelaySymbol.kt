package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val DELAY_URI = "org.balch.orpheus.plugins.delay"

enum class DelaySymbol(
    override val symbol: Symbol,
    override val uri: String = DELAY_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    FEEDBACK("feedback", displayName = "Feedback"),
    MIX("mix", displayName = "Mix"),
    TIME_1("time_1", displayName = "Time 1"),
    TIME_2("time_2", displayName = "Time 2"),
    MOD_DEPTH_1("mod_depth_1", displayName = "Mod Depth 1"),
    MOD_DEPTH_2("mod_depth_2", displayName = "Mod Depth 2"),
    STEREO_MODE("stereo_mode", displayName = "Stereo Mode"),
    MOD_SOURCE("mod_source_is_lfo", displayName = "Mod Source is LFO"),
    LFO_WAVEFORM("lfo_wave_is_triangle", displayName = "LFO Waveform is Triangle")
}
