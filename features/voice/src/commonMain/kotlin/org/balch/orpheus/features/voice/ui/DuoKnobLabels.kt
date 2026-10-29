package org.balch.orpheus.features.voice.ui

import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.SignalPath
import org.balch.orpheus.core.audio.knobMeaning

/** What a Duo knob shows: [short] under it, [glyph] as a caption, [long] while held. */
data class DuoKnobLabel(
    val short: String,
    val long: String,
    val glyph: String,
    val active: Boolean,
    val locked: Boolean,
)

private fun glyphFor(param: EngineParam): String = when (param) {
    EngineParam.HARMONICS -> "≡"
    EngineParam.TIMBRE -> "♯"
    EngineParam.MORPH -> "ψ"
}

/**
 * The Duo panel label for [param] on [engine]. On DX the harmonics knob is the patch selector,
 * so it locks and names the patch [harmonics] picks; the panel sounds the stored patch.
 */
fun duoKnobLabel(engine: OrpheusEngineId, param: EngineParam, harmonics: Float): DuoKnobLabel {
    val glyph = glyphFor(param)
    if (param == EngineParam.HARMONICS) {
        EngineCatalog.patchAt(engine, harmonics)?.let { patch ->
            val long = EngineCatalog.labelFor(EngineChoice.Patch(patch))
            return DuoKnobLabel(patch.name, long, glyph, active = true, locked = true)
        }
    }
    val meaning = knobMeaning(engine, param, SignalPath.PANEL)
        ?: return DuoKnobLabel("-", "not playable on the panel", glyph, active = false, locked = false)
    return DuoKnobLabel(meaning.short, meaning.long, glyph, meaning.active, locked = false)
}
