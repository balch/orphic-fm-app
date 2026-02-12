package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val EVO_URI = "org.balch.orpheus.plugins.evo"

enum class EvoSymbol(
    override val symbol: Symbol,
    override val uri: String = EVO_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    DEPTH("depth", displayName = "Depth"),
    RATE("rate", displayName = "Rate"),
    VARIATION("variation", displayName = "Variation")
}
