package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val WARPS_URI = "org.balch.orpheus.plugins.warps"

enum class WarpsSymbol(
    override val symbol: Symbol,
    override val uri: String = WARPS_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    ALGORITHM("algorithm", displayName = "Algorithm"),
    TIMBRE("timbre", displayName = "Timbre"),
    LEVEL1("level1", displayName = "Level 1"),
    LEVEL2("level2", displayName = "Level 2"),
    MIX("mix", displayName = "Mix"),
    CARRIER_SOURCE("carrier_source", displayName = "Carrier Source"),
    MODULATOR_SOURCE("modulator_source", displayName = "Modulator Source")
}
