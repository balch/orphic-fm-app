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
    DROP_A("drop_a", displayName = "Drop A"),
    DROP_B("drop_b", displayName = "Drop B"),
}

@Serializable
enum class DjSource(val sourceId: Int, val label: String) {
    SYNTH(0, "Synth"),
    DRUMS(1, "Drums"),
    BASS(2, "Bass"),
    MASTER(3, "Feedback"),
    SUM(4, "8-Track"),
}

@Serializable
enum class DjDrop(val id: Int, val label: String, val weight: Int) {
    NONE(0, "—", 0),
    FILTER(1,  "FILTER",  3),
    BRAKE(2,   "BRAKE",   1),   // lower prob — stoppy
    STUTTER(3, "STUTTER", 3),
    FREEZE(4,  "FREEZE",  1),   // lower prob — can be quiet
    OCTAVE(5,  "OCTAVE",  3),   // subharmonic bass layer
    PHASER(6,  "PHASER",  3),   // swirly phase sweep
    ECHO(7,    "ECHO",    3),   // dub-style delay + feedback
    RING(8,    "RING",    3),   // ring-mod metallic color
}
