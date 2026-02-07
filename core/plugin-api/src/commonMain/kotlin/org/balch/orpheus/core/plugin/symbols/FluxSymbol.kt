package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val FLUX_URI = "org.balch.orpheus.plugins.flux"

enum class FluxSymbol(
    override val symbol: Symbol,
    override val uri: String = FLUX_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    SPREAD("spread", displayName = "Spread"),
    BIAS("bias", displayName = "Bias"),
    STEPS("steps", displayName = "Steps"),
    DEJAVU("dejavu", displayName = "Déjà Vu"),
    LENGTH("length", displayName = "Length"),
    SCALE("scale", displayName = "Scale"),
    RATE("rate", displayName = "Rate"),
    JITTER("jitter", displayName = "Jitter"),
    PROBABILITY("probability", displayName = "Probability"),
    GATE_LENGTH("gatelength", displayName = "Gate Length"),
    CLOCK_SOURCE("clock_source", displayName = "Clock Source")
}
