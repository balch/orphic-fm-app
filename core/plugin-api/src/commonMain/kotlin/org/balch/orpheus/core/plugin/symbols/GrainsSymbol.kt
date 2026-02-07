package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val GRAINS_URI = "org.balch.orpheus.plugins.grains"

enum class GrainsSymbol(
    override val symbol: Symbol,
    override val uri: String = GRAINS_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    POSITION("position", displayName = "Position"),
    SIZE("size", displayName = "Size"),
    PITCH("pitch", displayName = "Pitch"),
    DENSITY("density", displayName = "Density"),
    TEXTURE("texture", displayName = "Texture"),
    DRY_WET("dry_wet", displayName = "Dry/Wet"),
    FREEZE("freeze", displayName = "Freeze"),
    TRIGGER("trigger", displayName = "Trigger"),
    MODE("mode", displayName = "Mode")
}
