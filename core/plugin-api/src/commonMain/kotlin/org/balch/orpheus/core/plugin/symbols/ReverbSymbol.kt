package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val REVERB_URI = "org.balch.orpheus.plugins.reverb"

enum class ReverbSymbol(
    override val symbol: Symbol,
    override val uri: String = REVERB_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    AMOUNT("amount", displayName = "Amount"),
    TIME("time", displayName = "Time"),
    DAMPING("damping", displayName = "Damping"),
    DIFFUSION("diffusion", displayName = "Diffusion")
}
