package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val VIBRATO_URI = "org.balch.orpheus.plugins.vibrato"

enum class VibratoSymbol(
    override val symbol: Symbol,
    override val uri: String = VIBRATO_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    DEPTH("depth", displayName = "Depth"),
    RATE("rate", displayName = "Rate")
}
