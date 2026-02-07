package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

/**
 * DuoLFO Plugin URI.
 * The plugin provides two LFO oscillators (A & B) with logical AND/OR combination.
 */
const val DUO_LFO_URI = "org.balch.orpheus.plugins.duolfo"


/**
 * Exhaustive enum of all DuoLfo plugin port symbols.
 * Used by both ViewModels and the plugin implementation.
 */
enum class DuoLfoSymbol(
    override val symbol: Symbol,
    override val uri: String = DUO_LFO_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MODE("mode", displayName = "Mode"),
    LINK("link", displayName = "Link"),
    TRIANGLE_MODE("triangle_mode", displayName = "Triangle Mode"),
    FREQ_A("freq_a", displayName = "Frequency A"),
    FREQ_B("freq_b", displayName = "Frequency B")
}
