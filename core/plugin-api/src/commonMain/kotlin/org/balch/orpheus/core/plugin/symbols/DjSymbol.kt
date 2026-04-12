package org.balch.orpheus.core.plugin.symbols

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val DJ_URI = "org.balch.orpheus.plugins.dj"

enum class DjSymbol(
    override val symbol: Symbol,
    override val uri: String = DJ_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    WET_A("wet_a", displayName = "Wet A"),
    WET_B("wet_b", displayName = "Wet B"),
    SOURCE_A("source_a", displayName = "Source A"),
    SOURCE_B("source_b", displayName = "Source B"),
    VELOCITY_A("velocity_a", displayName = "Velocity A"),
    VELOCITY_B("velocity_b", displayName = "Velocity B"),
    FROZEN_A("frozen_a", displayName = "Frozen A"),
    FROZEN_B("frozen_b", displayName = "Frozen B"),
    CROSSFADER("crossfader", displayName = "Crossfader"),
    DELAY_SEND("delay_send", displayName = "Delay Send"),
    REVERB_SEND("reverb_send", displayName = "Reverb Send"),
}

@Serializable
enum class DjSource(val sourceId: Int, val label: String) {
    SYNTH(0, "Synth"),
    DRUMS(1, "Drums"),
    BASS(2, "Bass"),
    MASTER(3, "Feedback"),
    SUM(4, "8-Track"),
}
