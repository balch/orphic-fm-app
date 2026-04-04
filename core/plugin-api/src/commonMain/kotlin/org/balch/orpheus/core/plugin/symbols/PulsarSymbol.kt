package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val PULSAR_URI = "org.balch.orpheus.plugins.pulsar"

enum class PulsarSymbol(
    override val symbol: Symbol,
    override val uri: String = PULSAR_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    PLAYING("playing", displayName = "Playing"),
    SCENE("scene", displayName = "Scene"),
    ENERGY("energy", displayName = "Energy"),
    COMPLEXITY("complexity", displayName = "Complexity"),
    SPACE("space", displayName = "Space"),
    MOOD("mood", displayName = "Mood"),
    BPM("bpm", displayName = "BPM"),
    DELAY_SEND("delay_send", displayName = "Delay Send"),
    REVERB_SEND("reverb_send", displayName = "Reverb Send"),
    ROOT_NOTE("root_note", displayName = "Root Note"),
    SCALE("scale", displayName = "Scale"),
    MIX("mix", displayName = "Mix"),
    PERC_MIX("perc_mix", displayName = "Percussion Mix"),
    ENVELOPE_MODE("envelope_mode", displayName = "Envelope Mode"),
    TRACK_0_ENGINE_EDM("track_0_engine_edm", displayName = "Track 0 EDM Engine"),
    TRACK_0_ENGINE_SPACE("track_0_engine_space", displayName = "Track 0 Space Engine"),
    TRACK_1_ENGINE_EDM("track_1_engine_edm", displayName = "Track 1 EDM Engine"),
    TRACK_1_ENGINE_SPACE("track_1_engine_space", displayName = "Track 1 Space Engine"),
    TRACK_2_ENGINE_EDM("track_2_engine_edm", displayName = "Track 2 EDM Engine"),
    TRACK_2_ENGINE_SPACE("track_2_engine_space", displayName = "Track 2 Space Engine"),
    TRACK_3_ENGINE_EDM("track_3_engine_edm", displayName = "Track 3 EDM Engine"),
    TRACK_3_ENGINE_SPACE("track_3_engine_space", displayName = "Track 3 Space Engine"),
    TRACK_4_ENGINE_EDM("track_4_engine_edm", displayName = "Track 4 EDM Engine"),
    TRACK_4_ENGINE_SPACE("track_4_engine_space", displayName = "Track 4 Space Engine"),
    TRACK_5_ENGINE_EDM("track_5_engine_edm", displayName = "Track 5 EDM Engine"),
    TRACK_5_ENGINE_SPACE("track_5_engine_space", displayName = "Track 5 Space Engine"),
    TRACK_6_ENGINE_EDM("track_6_engine_edm", displayName = "Track 6 EDM Engine"),
    TRACK_6_ENGINE_SPACE("track_6_engine_space", displayName = "Track 6 Space Engine"),
    TRACK_7_ENGINE_EDM("track_7_engine_edm", displayName = "Track 7 EDM Engine"),
    TRACK_7_ENGINE_SPACE("track_7_engine_space", displayName = "Track 7 Space Engine"),
}
