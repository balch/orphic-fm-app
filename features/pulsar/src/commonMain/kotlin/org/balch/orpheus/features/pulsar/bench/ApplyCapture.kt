package org.balch.orpheus.features.pulsar.bench

import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * Writes each row's `new` value into the field [capture] read it from, so the result with no layers
 * plays what this vibe played with them. Fields no row names are kept. Throws for a row with no field.
 */
fun Vibe.withCapture(rows: List<CaptureRow>): Vibe = rows.fold(this) { v, r ->
    when (r.scope) {
        Scope.VIBE -> v.applyVibeRow(r)
        Scope.SECTION -> v.applySectionRow(r)
    }
}

private fun Vibe.applyVibeRow(r: CaptureRow): Vibe {
    val n = r.new
    if (r.field == CaptureField.MACRO) {
        return when (r.macro ?: badRow(r, "a macro row needs a macro")) {
            Macro.ENERGY -> copy(energy = n)
            Macro.COMPLEXITY -> copy(complexity = n)
            Macro.MOOD -> copy(mood = n)
            Macro.SPACE -> copy(space = n)
        }
    }
    return track(r) { t ->
        when (r.field) {
            CaptureField.VOLUME -> t.copy(engineEdm = t.engineEdm.copy(volume = n))
            CaptureField.VOLUME_SPACE -> t.copy(engineSpace = t.engineSpace.copy(volume = n))
            CaptureField.PAN -> t.copy(pan = n)
            CaptureField.REVERB_SEND -> t.edm { copy(reverbSend = n) }
            CaptureField.REVERB_SEND_SPACE -> t.space { copy(reverbSend = n) }
            CaptureField.DELAY_SEND -> t.edm { copy(delaySend = n) }
            CaptureField.DELAY_SEND_SPACE -> t.space { copy(delaySend = n) }
            CaptureField.MACRO, CaptureField.MACRO_RAMP -> badRow(r, "no whole-vibe ${r.field} field")
        }
    }
}

// A section override replaces the track's value while the section plays, so one value covers both slots.
private fun Vibe.applySectionRow(r: CaptureRow): Vibe = section(r) { s ->
    val n = r.new
    when (r.field) {
        CaptureField.VOLUME -> s.override(r) { copy(volume = n) }
        CaptureField.REVERB_SEND -> s.override(r) { copy(reverbSend = n) }
        CaptureField.DELAY_SEND -> s.override(r) { copy(delaySend = n) }
        CaptureField.MACRO -> s.copy(macroOverrides = s.macroOverrides.with(r, n))
        CaptureField.MACRO_RAMP -> s.copy(rampTo = s.rampTo.with(r, n))
        else -> badRow(r, "no section ${r.field} field")
    }
}

private fun Vibe.track(r: CaptureRow, edit: (TrackVoice) -> TrackVoice): Vibe {
    val t = r.track?.takeIf { it in tracks.indices } ?: badRow(r, "track ${r.track} out of range")
    return copy(tracks = tracks.toMutableList().also { it[t] = edit(it[t]) })
}

private fun Vibe.section(r: CaptureRow, edit: (Section) -> Section): Vibe {
    val sections = arrangement?.sections.orEmpty()
    val s = r.section?.takeIf { it in sections.indices } ?: badRow(r, "section ${r.section} out of range")
    return copy(arrangement = arrangement!!.copy(sections = sections.toMutableList().also { it[s] = edit(it[s]) }))
}

private fun TrackVoice.edm(edit: OrpheusEngine.() -> OrpheusEngine) = copy(engineEdm = engineEdm.edit())

private fun TrackVoice.space(edit: OrpheusEngine.() -> OrpheusEngine) = copy(engineSpace = engineSpace.edit())

private fun Section.override(r: CaptureRow, edit: TrackSectionOverride.() -> TrackSectionOverride): Section {
    val t = r.track?.takeIf { it in 0 until 8 } ?: badRow(r, "track ${r.track} out of range")
    val overrides = trackOverrides.orEmpty()
    return copy(trackOverrides = overrides + (t to (overrides[t] ?: TrackSectionOverride()).edit()))
}

private fun MacroOverrides?.with(r: CaptureRow, n: Float): MacroOverrides {
    val o = this ?: MacroOverrides()
    return when (r.macro ?: badRow(r, "a macro row needs a macro")) {
        Macro.ENERGY -> o.copy(energy = n)
        Macro.COMPLEXITY -> o.copy(complexity = n)
        Macro.MOOD -> o.copy(mood = n)
        Macro.SPACE -> o.copy(space = n)
    }
}

private fun badRow(r: CaptureRow, why: String): Nothing = throw IllegalArgumentException("capture row $r: $why")
