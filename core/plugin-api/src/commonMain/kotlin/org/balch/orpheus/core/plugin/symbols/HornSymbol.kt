package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val HORN_URI = "org.balch.orpheus.plugins.horn"

enum class HornSymbol(
    override val symbol: Symbol,
    override val uri: String = HORN_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    SPEED("speed", displayName = "Speed"),
    RATIO("ratio", displayName = "Ratio"),
    DEPTH("depth", displayName = "Depth"),
    MIX("mix", displayName = "Mix"),
    BRAKE("brake", displayName = "Brake")
}
