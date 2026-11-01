package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val COWBELL_URI = "org.balch.orpheus.plugins.cowbell"

enum class CowbellSymbol(
    override val symbol: Symbol,
    override val uri: String = COWBELL_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    LEVEL("level", displayName = "Level"),
    METAL("metal", displayName = "Metal"),
    RING("ring", displayName = "Ring"),
    INTENSITY("intensity", displayName = "Intensity"),
    ENGINE("engine", displayName = "Engine"),
    OCTAVE("octave", displayName = "Octave"),
    GRID("grid", displayName = "Grid"),
    GRID_STRENGTH("grid_strength", displayName = "Grid Strength"),
    MODE("mode", displayName = "Mode"),
    FILL("fill", displayName = "Fill"),
    STRIKE("strike", displayName = "Strike"),
    GATE("gate", displayName = "Gate"),
    BEND("bend", displayName = "Bend"),
    STRUCTURE_OFFSET("structure_offset", displayName = "Structure Offset"),
    POSITION_OFFSET("position_offset", displayName = "Position Offset"),
    BEND_SCALE("bend_scale", displayName = "Bend To Scale"),
    LIVE("live", displayName = "Live"),
    MORE("more", displayName = "More")
}
