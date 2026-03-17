package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

/**
 * Lorenz Attractor Plugin URI.
 * Chaotic modulation source (from Streams).
 */
const val LORENZ_URI = "org.balch.orpheus.plugins.lorenz"

/**
 * Exhaustive enum of all Lorenz plugin port symbols.
 * Used by both ViewModels and the plugin implementation.
 */
enum class LorenzSymbol(
    override val symbol: Symbol,
    override val uri: String = LORENZ_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    RATE("rate", displayName = "Rate"),
    BALANCE("balance", displayName = "Smooth"),
    BYPASS("bypass", displayName = "Bypass")
}
