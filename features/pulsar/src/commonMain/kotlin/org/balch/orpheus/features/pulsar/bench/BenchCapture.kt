package org.balch.orpheus.features.pulsar.bench

import kotlin.math.abs
import kotlin.math.log10
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.Vibe

@Serializable
enum class CaptureField { VOLUME, VOLUME_SPACE, PAN, REVERB_SEND, REVERB_SEND_SPACE, DELAY_SEND, DELAY_SEND_SPACE, MACRO, MACRO_RAMP }

/**
 * One vibe-owned field change. [clampedDb] is the dB that volume clamping at 1.0 swallowed.
 * SECTION macro rows hold the section multiplier (old and new), not the knob.
 */
@Serializable
data class CaptureRow(
    val scope: Scope,
    val section: Int?,
    val sectionName: String?,
    val track: Int?,
    val field: CaptureField,
    val macro: Macro?,
    val old: Float,
    val new: Float,
    val clampedDb: Float = 0f,
)

private const val EPS = 1e-4f

private fun volumeRow(
    scope: Scope, section: Int?, name: String?, t: Int, field: CaptureField, old: Float, wanted: Float,
): CaptureRow? {
    val over = wanted > 1f
    val new = wanted.coerceIn(0f, 1f)
    if (abs(new - old) <= EPS) return null
    val clampedDb = if (over) (20.0 * log10(wanted.toDouble())).toFloat() else 0f
    return CaptureRow(scope, section, name, t, field, null, old, new, clampedDb)
}

private fun unitRow(
    scope: Scope, section: Int?, name: String?, t: Int, field: CaptureField, old: Float, wanted: Float, lo: Float = 0f,
): CaptureRow? {
    val new = wanted.coerceIn(lo, 1f)
    return if (abs(new - old) <= EPS) null else CaptureRow(scope, section, name, t, field, null, old, new)
}

/**
 * Turns the layers into field changes: VIBE rows first, then sections in index order.
 *
 * Rows hold file values, never the app's knob, which is user data and can differ from the file.
 * A VIBE macro row is old = the file's default, new = the knob the user set on the bench, so an
 * untouched macro writes no row. A SECTION macro row keeps what was heard over the default the
 * file will hold: the captured knob if the whole-vibe macro moved, else the file's default.
 */
fun capture(vibe: Vibe, layers: BenchLayers): List<CaptureRow> {
    val rows = mutableListOf<CaptureRow>()
    val vl = layers.vibe
    val sections = vibe.arrangement?.sections.orEmpty()

    for (t in vibe.tracks.indices) {
        val track = vibe.tracks[t]
        val gain = layers.vibeGain(t)
        volumeRow(Scope.VIBE, null, null, t, CaptureField.VOLUME, track.engineEdm.volume, track.engineEdm.volume * gain)?.let(rows::add)
        volumeRow(Scope.VIBE, null, null, t, CaptureField.VOLUME_SPACE, track.engineSpace.volume, track.engineSpace.volume * gain)?.let(rows::add)
        vl.pan.getOrNull(t)?.let { p ->
            val new = p.coerceIn(-1f, 1f)
            if (abs(new - track.pan) > EPS) {
                rows += CaptureRow(Scope.VIBE, null, null, t, CaptureField.PAN, null, track.pan, new)
            }
        }
        val rr = vl.reverbRatio.getOrNull(t) ?: 1f
        unitRow(Scope.VIBE, null, null, t, CaptureField.REVERB_SEND, track.engineEdm.reverbSend, track.engineEdm.reverbSend * rr)?.let(rows::add)
        unitRow(Scope.VIBE, null, null, t, CaptureField.REVERB_SEND_SPACE, track.engineSpace.reverbSend, track.engineSpace.reverbSend * rr)?.let(rows::add)
        val dr = vl.delayRatio.getOrNull(t) ?: 1f
        unitRow(Scope.VIBE, null, null, t, CaptureField.DELAY_SEND, track.engineEdm.delaySend, track.engineEdm.delaySend * dr)?.let(rows::add)
        unitRow(Scope.VIBE, null, null, t, CaptureField.DELAY_SEND_SPACE, track.engineSpace.delaySend, track.engineSpace.delaySend * dr)?.let(rows::add)
    }
    for ((m, knob) in vl.macros) {
        val old = m.vibeDefault(vibe)
        val new = knob.coerceIn(0f, 1f)
        if (abs(new - old) > EPS) {
            rows += CaptureRow(Scope.VIBE, null, null, null, CaptureField.MACRO, m, old, new)
        }
    }

    for ((s, section) in sections.withIndex()) {
        val sl = layers.sections[s]
        val name = section.name
        for (t in vibe.tracks.indices) {
            val o = section.trackOverrides?.get(t)
            val edm = layers.activeEngine(vibe, s, t)
            val sDb = sl?.levelDb?.getOrNull(t) ?: 0f
            val gain = layers.auditionGain(t, s)
            if (o?.volume != null) {
                volumeRow(Scope.SECTION, s, name, t, CaptureField.VOLUME, o.volume!!, o.volume!! * gain)?.let(rows::add)
            } else if (abs(sDb) > 0f) {
                volumeRow(Scope.SECTION, s, name, t, CaptureField.VOLUME, edm.volume, edm.volume * gain)?.let(rows::add)
            }
            val rr = vl.reverbRatio.getOrNull(t) ?: 1f
            val absR = sl?.reverbSend?.getOrNull(t)
            val baseR = absR ?: o?.reverbSend
            if (baseR != null) {
                unitRow(Scope.SECTION, s, name, t, CaptureField.REVERB_SEND, o?.reverbSend ?: edm.reverbSend, baseR * rr)?.let(rows::add)
            }
            val dr = vl.delayRatio.getOrNull(t) ?: 1f
            val absD = sl?.delaySend?.getOrNull(t)
            val baseD = absD ?: o?.delaySend
            if (baseD != null) {
                unitRow(Scope.SECTION, s, name, t, CaptureField.DELAY_SEND, o?.delaySend ?: edm.delaySend, baseD * dr)?.let(rows::add)
            }
        }
        if (sl != null) {
            for ((m, heard) in sl.macrosHeard) {
                val knob = vl.macros[m] ?: m.vibeDefault(vibe)
                if (knob <= EPS) continue
                val old = section.macroOverrides.multiplier(m) ?: 1f
                val new = heard / knob
                if (abs(new - old) <= EPS) continue
                rows += CaptureRow(Scope.SECTION, s, name, null, CaptureField.MACRO, m, old, new)
                val ramp = section.rampTo.multiplier(m)
                if (ramp != null && old > EPS) {
                    rows += CaptureRow(Scope.SECTION, s, name, null, CaptureField.MACRO_RAMP, m, ramp, ramp * new / old)
                }
            }
        }
    }
    return rows
}
