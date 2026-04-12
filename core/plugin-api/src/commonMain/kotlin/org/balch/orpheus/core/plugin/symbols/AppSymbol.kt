package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val APP_URI = "org.balch.orpheus.plugins.app"

enum class AppSymbol(
    override val symbol: Symbol,
    override val uri: String = APP_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MUTED("muted", displayName = "Muted"),
}
