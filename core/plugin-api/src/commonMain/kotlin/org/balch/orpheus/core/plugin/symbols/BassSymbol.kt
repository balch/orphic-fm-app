package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val BASS_URI = "org.balch.orpheus.plugins.bass"

enum class BassSymbol(
    override val symbol: Symbol,
    override val uri: String = BASS_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    ENGINE("engine", displayName = "Engine"),
    ROOT_NOTE("root_note", displayName = "Root Note"),
    SCALE("scale", displayName = "Scale"),
    CLOCK_DIV("clock_div", displayName = "Clock Division"),
    STEP_COUNT("step_count", displayName = "Step Count"),
    MUTATION("mutation", displayName = "Mutation"),
    CUTOFF("cutoff", displayName = "Cutoff"),
    RESONANCE("resonance", displayName = "Resonance"),
    ENVELOPE("envelope", displayName = "Envelope"),
    OVERDRIVE("overdrive", displayName = "Overdrive"),
    COMPRESSOR("compressor", displayName = "Compressor"),
    MIX("mix", displayName = "Mix"),
    LFO_MIX("lfo_mix", displayName = "LFO Mix"),
    TRIGGER_SOURCE("trigger_source", displayName = "Trigger Source"),
    PITCH_SOURCE("pitch_source", displayName = "Pitch Source"),
    TIMBRE_SOURCE("timbre_source", displayName = "Timbre Source"),
    ACCENT_AMOUNT("accent_amount", displayName = "Accent"),
    JITTER("jitter", displayName = "Jitter"),
    FX_SEND("fx_send", displayName = "FX Send"),
}
