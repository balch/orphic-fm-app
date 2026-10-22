package org.balch.orpheus.features.pulsar.bench

import kotlin.math.pow
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.Vibe

/** The four user-facing macros the bench can move. */
@Serializable
enum class Macro { ENERGY, COMPLEXITY, MOOD, SPACE }

/** Whole-vibe edits: relative moves per track plus absolute macro knob values. */
data class VibeLayer(
    val levelDb: List<Float> = List(8) { 0f },
    val pan: List<Float?> = List(8) { null },
    val reverbRatio: List<Float> = List(8) { 1f },
    val delayRatio: List<Float> = List(8) { 1f },
    val macros: Map<Macro, Float> = emptyMap(),
)

/** Edits for one section: dB on top of the vibe layer, absolute sends, macros as heard. */
data class SectionLayer(
    val levelDb: List<Float> = List(8) { 0f },
    val reverbSend: List<Float?> = List(8) { null },
    val delaySend: List<Float?> = List(8) { null },
    val macrosHeard: Map<Macro, Float> = emptyMap(),
)

data class BenchLayers(
    val vibe: VibeLayer = VibeLayer(),
    val sections: Map<Int, SectionLayer> = emptyMap(),
)

@Serializable
enum class Scope { VIBE, SECTION }

/** [levelDb] is the layer offset (vibe + section dB); pan and sends are final values. */
data class EffectiveTrack(val levelDb: Float, val pan: Float, val reverbSend: Float, val delaySend: Float)

internal fun dbToGain(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

/** Gain ceiling shared by live audition and capture: 1.5 (about +3.52 dB). */
internal const val MAX_GAIN = 1.5f

/** A closed fader: a level at or below this plays (and captures) as exact silence, while the layers stay finite. */
const val SILENT_DB = -80f

/** Fader dB to linear gain: 0 at or below [SILENT_DB], capped at [MAX_GAIN]. */
internal fun levelGain(db: Float): Float = if (db <= SILENT_DB) 0f else dbToGain(db).coerceAtMost(MAX_GAIN)

/** Whole-vibe fader gain as heard, capped at [MAX_GAIN]. */
internal fun BenchLayers.vibeGain(t: Int): Float = levelGain(vibeDb(t))

internal fun Macro.vibeDefault(vibe: Vibe): Float = when (this) {
    Macro.ENERGY -> vibe.energy
    Macro.COMPLEXITY -> vibe.complexity
    Macro.MOOD -> vibe.mood
    Macro.SPACE -> vibe.space
}

internal fun MacroOverrides?.multiplier(m: Macro): Float? = when (m) {
    Macro.ENERGY -> this?.energy
    Macro.COMPLEXITY -> this?.complexity
    Macro.MOOD -> this?.mood
    Macro.SPACE -> this?.space
}

/**
 * Energy the bench plays in [section]: heard override, else (knob or [baseEnergy]) x section multiplier.
 * [baseEnergy] is the app's live knob for what plays now, and the file's energy for a capture.
 */
internal fun BenchLayers.heardEnergy(vibe: Vibe, section: Int, baseEnergy: Float = vibe.energy): Float {
    sections[section]?.macrosHeard?.get(Macro.ENERGY)?.let { return it.coerceIn(0f, 1f) }
    val mult = vibe.arrangement?.sections?.getOrNull(section)?.macroOverrides?.energy ?: 1f
    return ((this.vibe.macros[Macro.ENERGY] ?: baseEnergy) * mult).coerceIn(0f, 1f)
}

/** The voice a section plays: EDM when its heard energy is at least 0.5, else Space. */
internal fun BenchLayers.activeEngine(vibe: Vibe, section: Int, t: Int, baseEnergy: Float = vibe.energy) =
    if (heardEnergy(vibe, section, baseEnergy) >= 0.5f) vibe.tracks[t].engineEdm else vibe.tracks[t].engineSpace

/** Where a track's sends start: the section override, else the slot the section plays. */
private fun BenchLayers.sectionBase(vibe: Vibe, section: Int, t: Int, reverb: Boolean, baseEnergy: Float): Float {
    val o = vibe.arrangement?.sections?.getOrNull(section)?.trackOverrides?.get(t)
    val track = activeEngine(vibe, section, t, baseEnergy)
    return if (reverb) o?.reverbSend ?: track.reverbSend else o?.delaySend ?: track.delaySend
}

/**
 * Vibe/section base combined with the layers; sends follow the [overlay] rule and the active slot's send.
 * [liveEnergy] is the app's energy knob, which picks the slot when the bench has not moved energy.
 */
fun BenchLayers.effective(vibe: Vibe, section: Int, t: Int, liveEnergy: Float = vibe.energy): EffectiveTrack {
    val ov = overlay(vibe)
    return EffectiveTrack(
        levelDb = vibeDb(t) + (sections[section]?.levelDb?.getOrNull(t) ?: 0f),
        pan = ov.pan(t, vibe.tracks[t].pan),
        reverbSend = ov.reverbSend(t, section, sectionBase(vibe, section, t, reverb = true, liveEnergy)),
        delaySend = ov.delaySend(t, section, sectionBase(vibe, section, t, reverb = false, liveEnergy)),
    )
}

private fun BenchLayers.vibeDb(t: Int): Float = vibe.levelDb.getOrNull(t) ?: 0f

/** Linear gain for the audition fader path: 10^((vibe+section dB)/20), clamped 0..1.5, and 0 at [SILENT_DB]. */
fun BenchLayers.auditionGain(t: Int, section: Int): Float =
    levelGain(vibeDb(t) + (sections[section]?.levelDb?.getOrNull(t) ?: 0f))

/**
 * Section absolute sends win first, then the whole-vibe ratio multiplies the result,
 * so whole-vibe moves stay relative and keep section differences.
 */
fun BenchLayers.overlay(vibe: Vibe): MixOverlay = object : MixOverlay {
    override fun pan(t: Int, vibePan: Float): Float =
        this@overlay.vibe.pan.getOrNull(t) ?: vibePan

    override fun reverbSend(t: Int, section: Int, base: Float): Float {
        val s = sections[section]?.reverbSend?.getOrNull(t) ?: base
        return (s * (this@overlay.vibe.reverbRatio.getOrNull(t) ?: 1f)).coerceIn(0f, 1f)
    }

    override fun delaySend(t: Int, section: Int, base: Float): Float {
        val s = sections[section]?.delaySend?.getOrNull(t) ?: base
        return (s * (this@overlay.vibe.delayRatio.getOrNull(t) ?: 1f)).coerceIn(0f, 1f)
    }
}

/**
 * Base port value so that base x the section's multiplier equals the heard value. [live] is the
 * app's knob, which plays wherever no layer moved [m]; it can differ from the vibe file.
 */
fun BenchLayers.macroPortValue(vibe: Vibe, section: Int, m: Macro, live: Float = m.vibeDefault(vibe)): Float {
    val knob = this.vibe.macros[m] ?: live
    val heard = sections[section]?.macrosHeard?.get(m) ?: return knob
    val mult = vibe.arrangement?.sections?.getOrNull(section)?.macroOverrides.multiplier(m) ?: 1f
    return if (mult == 0f) knob else heard / mult
}

/** Section edits only make sense when the vibe has an arrangement with at least two sections. */
fun Vibe.sectionScopeAvailable(): Boolean = (arrangement?.sections?.size ?: 0) >= 2
