package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val RESONATOR_URI = "org.balch.orpheus.plugins.resonator"

enum class ResonatorSymbol(
    override val symbol: Symbol,
    override val uri: String = RESONATOR_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MODE("mode", displayName = "Mode"),
    TARGET_MIX("target_mix", displayName = "Target Mix"),
    STRUCTURE("structure", displayName = "Structure"),
    BRIGHTNESS("brightness", displayName = "Brightness"),
    DAMPING("damping", displayName = "Damping"),
    POSITION("position", displayName = "Position"),
    MIX("mix", displayName = "Mix"),
    SNAP_BACK("snap_back", displayName = "Snap Back")
}
