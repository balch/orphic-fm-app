package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val DISTORTION_URI = "org.balch.orpheus.plugins.distortion"

enum class DistortionSymbol(
    override val symbol: Symbol,
    override val uri: String = DISTORTION_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    DRIVE("drive", displayName = "Drive"),
    MIX("mix", displayName = "Mix"),
    DRY_LEVEL("dry_level", displayName = "Dry Level")
}
