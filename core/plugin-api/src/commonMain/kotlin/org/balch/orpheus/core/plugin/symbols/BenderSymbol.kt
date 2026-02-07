package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val BENDER_URI = "org.balch.orpheus.plugins.bender"

enum class BenderSymbol(
    override val symbol: Symbol,
    override val uri: String = BENDER_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    BEND("bend", displayName = "Bend"),
    MAX_BEND("max_bend", displayName = "Max Bend Semitones"),
    RANDOM_DEPTH("random_depth", displayName = "Random Depth"),
    TIMBRE_MOD("timbre_mod", displayName = "Timbre Modulation"),
    SPRING_VOL("spring_vol", displayName = "Spring Volume"),
    TENSION_VOL("tension_vol", displayName = "Tension Volume")
}
