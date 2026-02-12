package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val VIZ_URI = "org.balch.orpheus.plugins.viz"

enum class VizSymbol(
    override val symbol: Symbol,
    override val uri: String = VIZ_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    KNOB_1("knob_1", displayName = "Knob 1"),
    KNOB_2("knob_2", displayName = "Knob 2")
}
