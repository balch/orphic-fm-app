package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val TIDES_URI = "org.balch.orpheus.plugins.tides"

enum class TidesSymbol(
    override val symbol: Symbol,
    override val uri: String = TIDES_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    FREQUENCY("frequency"),
    SLOPE("slope"),
    SHAPE("shape"),
    SMOOTHNESS("smoothness"),
    SHIFT("shift"),
    MIX("mix"),
    CLOCK_OFFSET("clock_offset", displayName = "Clock Offset"),
    RAMP_MODE("ramp_mode", displayName = "Ramp Mode"),
    OUTPUT_MODE("output_mode", displayName = "Output Mode"),
    RANGE("range"),
    GATE_SOURCE("gate_source", displayName = "Gate Source"),
    CLOCK_SOURCE("clock_source", displayName = "Clock Source"),
}
