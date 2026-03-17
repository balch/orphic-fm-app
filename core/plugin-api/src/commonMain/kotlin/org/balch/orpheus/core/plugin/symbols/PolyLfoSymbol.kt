package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

/**
 * PolyLFO Plugin URI.
 * 4-channel poly LFO with shape morphing, spread, and coupling (from Frames).
 */
const val POLY_LFO_URI = "org.balch.orpheus.plugins.polylfo"

/**
 * Exhaustive enum of all PolyLFO plugin port symbols.
 * Used by both ViewModels and the plugin implementation.
 */
enum class PolyLfoSymbol(
    override val symbol: Symbol,
    override val uri: String = POLY_LFO_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    SHAPE("shape", displayName = "Shape"),
    SHAPE_SPREAD("shape_spread", displayName = "Shape Spread"),
    SPREAD("spread", displayName = "Spread"),
    COUPLING("coupling", displayName = "Coupling"),
    RATE("rate", displayName = "Rate"),
    BYPASS("bypass", displayName = "Bypass")
}
