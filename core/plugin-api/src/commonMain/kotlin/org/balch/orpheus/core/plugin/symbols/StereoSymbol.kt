package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val STEREO_URI = "org.balch.orpheus.plugins.stereo"

enum class StereoSymbol(
    override val symbol: Symbol,
    override val uri: String = STEREO_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MASTER_PAN("master_pan", displayName = "Master Pan"),
    MASTER_VOL("master_vol", displayName = "Master Volume"),
    VOICE_PAN_0("voice_pan_0", displayName = "Voice 0 Pan"),
    VOICE_PAN_1("voice_pan_1", displayName = "Voice 1 Pan"),
    VOICE_PAN_2("voice_pan_2", displayName = "Voice 2 Pan"),
    VOICE_PAN_3("voice_pan_3", displayName = "Voice 3 Pan"),
    VOICE_PAN_4("voice_pan_4", displayName = "Voice 4 Pan"),
    VOICE_PAN_5("voice_pan_5", displayName = "Voice 5 Pan"),
    VOICE_PAN_6("voice_pan_6", displayName = "Voice 6 Pan"),
    VOICE_PAN_7("voice_pan_7", displayName = "Voice 7 Pan"),
    VOICE_PAN_8("voice_pan_8", displayName = "Voice 8 Pan"),
    VOICE_PAN_9("voice_pan_9", displayName = "Voice 9 Pan"),
    VOICE_PAN_10("voice_pan_10", displayName = "Voice 10 Pan"),
    VOICE_PAN_11("voice_pan_11", displayName = "Voice 11 Pan")
}
