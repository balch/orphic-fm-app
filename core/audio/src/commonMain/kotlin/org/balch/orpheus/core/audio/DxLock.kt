package org.balch.orpheus.core.audio

import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol

private val DUO_HARMONICS_PREFIX = "duo_harmonics_"

/**
 * Returns the engine control key that governs a knob locked on DX engines, or null when the
 * knob is never locked. Accepts the bare id ("duo_harmonics_2") or the full "uri:symbol" key.
 */
fun dxLockEngineControl(controlId: String): String? {
    val bare = controlId.substringAfterLast(':')
    if (bare.startsWith(DUO_HARMONICS_PREFIX)) {
        val index = bare.removePrefix(DUO_HARMONICS_PREFIX).toIntOrNull()
        if (index != null && index in 0..5) return VoiceSymbol.duoEngine(index).controlId.key
    }
    return when (controlId) {
        "drums_bd_mod" -> DrumSymbol.BD_ENGINE.controlId.key
        "drums_sd_mod" -> DrumSymbol.SD_ENGINE.controlId.key
        "drums_hh_mod" -> DrumSymbol.HH_ENGINE.controlId.key
        else -> null
    }
}

/** True when [controlId] is the drum MOD knob, whose engine control stores an enum ordinal. */
fun isDrumModControl(controlId: String): Boolean = controlId.startsWith("drums_") && controlId.endsWith("_mod")
