package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val TTS_URI = "org.balch.orpheus.plugins.tts"

enum class TtsSymbol(
    override val symbol: Symbol,
    override val uri: String = TTS_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    RATE("rate", displayName = "Pitch"),
    SPEED("speed", displayName = "Speed"),
    VOLUME("volume", displayName = "Volume"),
    REVERB("reverb", displayName = "Reverb"),
    PHASER("phaser", displayName = "Phaser"),
    FEEDBACK("feedback", displayName = "Feedback")
}
