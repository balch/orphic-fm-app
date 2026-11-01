package org.balch.orpheus.features.pulsar.bench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One value a bench edit can move: a vibe-layer or section-layer level, pan, send ratio, macro or delay setting. */
@Serializable
sealed interface LayerField {
    val track: Int?
    val section: Int?

    @Serializable @SerialName("VibeLevel") data class VibeLevel(override val track: Int) : LayerField { override val section: Int? get() = null }
    @Serializable @SerialName("VibePan") data class VibePan(override val track: Int) : LayerField { override val section: Int? get() = null }
    @Serializable @SerialName("VibeReverb") data class VibeReverb(override val track: Int) : LayerField { override val section: Int? get() = null }
    @Serializable @SerialName("VibeDelay") data class VibeDelay(override val track: Int) : LayerField { override val section: Int? get() = null }
    @Serializable @SerialName("VibeMacro") data class VibeMacro(val macro: Macro) : LayerField {
        override val track: Int? get() = null
        override val section: Int? get() = null
    }
    @Serializable @SerialName("SectionLevel") data class SectionLevel(override val section: Int, override val track: Int) : LayerField
    @Serializable @SerialName("SectionReverb") data class SectionReverb(override val section: Int, override val track: Int) : LayerField
    @Serializable @SerialName("SectionDelay") data class SectionDelay(override val section: Int, override val track: Int) : LayerField
    @Serializable @SerialName("SectionMacro") data class SectionMacro(override val section: Int, val macro: Macro) : LayerField {
        override val track: Int? get() = null
    }
    /** The delay's time or feedback for the whole vibe; [VibeDelay] is a track's send. */
    @Serializable @SerialName("VibeDelayFx") data class VibeDelayFx(val param: DelayFx) : LayerField {
        override val track: Int? get() = null
        override val section: Int? get() = null
    }
    /** The delay's time or feedback in one section; [SectionDelay] is a track's send. */
    @Serializable @SerialName("SectionDelayFx") data class SectionDelayFx(override val section: Int, val param: DelayFx) : LayerField {
        override val track: Int? get() = null
    }
}

/** A field's value before and after an edit; null reads as "as written". */
@Serializable
data class LayerWrite(val before: Float?, val after: Float?)

private const val TRACKS = 8

/** The field's value, with null for "as written": unset pan, send or macro, level 0f and ratio 1f. */
fun BenchLayers.valueOf(f: LayerField): Float? = when (f) {
    is LayerField.VibeLevel -> vibe.levelDb.getOrNull(f.track)?.takeIf { it != 0f }
    is LayerField.VibePan -> vibe.pan.getOrNull(f.track)
    is LayerField.VibeReverb -> vibe.reverbRatio.getOrNull(f.track)?.takeIf { it != 1f }
    is LayerField.VibeDelay -> vibe.delayRatio.getOrNull(f.track)?.takeIf { it != 1f }
    is LayerField.VibeMacro -> vibe.macros[f.macro]
    is LayerField.SectionLevel -> sections[f.section]?.levelDb?.getOrNull(f.track)?.takeIf { it != 0f }
    is LayerField.SectionReverb -> sections[f.section]?.reverbSend?.getOrNull(f.track)
    is LayerField.SectionDelay -> sections[f.section]?.delaySend?.getOrNull(f.track)
    is LayerField.SectionMacro -> sections[f.section]?.macrosHeard?.get(f.macro)
    is LayerField.VibeDelayFx -> vibe.delayFx[f.param]
    is LayerField.SectionDelayFx -> sections[f.section]?.delayFx?.get(f.param)
}

private fun <T> List<T>.set(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }

private fun BenchLayers.withSection(s: Int, edit: (SectionLayer) -> SectionLayer): BenchLayers {
    val layer = edit(sections[s] ?: SectionLayer())
    return copy(sections = if (layer == SectionLayer()) sections - s else sections + (s to layer))
}

/** Sets the field; null restores its default, and a section layer left empty is dropped. */
fun BenchLayers.with(f: LayerField, v: Float?): BenchLayers = when (f) {
    is LayerField.VibeLevel -> copy(vibe = vibe.copy(levelDb = vibe.levelDb.set(f.track, v ?: 0f)))
    is LayerField.VibePan -> copy(vibe = vibe.copy(pan = vibe.pan.set(f.track, v)))
    is LayerField.VibeReverb -> copy(vibe = vibe.copy(reverbRatio = vibe.reverbRatio.set(f.track, v ?: 1f)))
    is LayerField.VibeDelay -> copy(vibe = vibe.copy(delayRatio = vibe.delayRatio.set(f.track, v ?: 1f)))
    is LayerField.VibeMacro ->
        copy(vibe = vibe.copy(macros = if (v == null) vibe.macros - f.macro else vibe.macros + (f.macro to v)))
    is LayerField.SectionLevel -> withSection(f.section) { it.copy(levelDb = it.levelDb.set(f.track, v ?: 0f)) }
    is LayerField.SectionReverb -> withSection(f.section) { it.copy(reverbSend = it.reverbSend.set(f.track, v)) }
    is LayerField.SectionDelay -> withSection(f.section) { it.copy(delaySend = it.delaySend.set(f.track, v)) }
    is LayerField.SectionMacro -> withSection(f.section) {
        it.copy(macrosHeard = if (v == null) it.macrosHeard - f.macro else it.macrosHeard + (f.macro to v))
    }
    is LayerField.VibeDelayFx ->
        copy(vibe = vibe.copy(delayFx = if (v == null) vibe.delayFx - f.param else vibe.delayFx + (f.param to v)))
    is LayerField.SectionDelayFx -> withSection(f.section) {
        it.copy(delayFx = if (v == null) it.delayFx - f.param else it.delayFx + (f.param to v))
    }
}

/** Every field whose value differs between this and [after]: tracks 0-7, every macro and delay setting, every section in either. */
fun BenchLayers.diff(after: BenchLayers): Map<LayerField, LayerWrite> {
    val out = LinkedHashMap<LayerField, LayerWrite>()
    fun check(f: LayerField) {
        val b = valueOf(f)
        val a = after.valueOf(f)
        if (a != b) out[f] = LayerWrite(b, a)
    }
    for (t in 0 until TRACKS) {
        check(LayerField.VibeLevel(t))
        check(LayerField.VibePan(t))
        check(LayerField.VibeReverb(t))
        check(LayerField.VibeDelay(t))
    }
    for (m in Macro.entries) check(LayerField.VibeMacro(m))
    for (p in DelayFx.entries) check(LayerField.VibeDelayFx(p))
    for (s in (sections.keys + after.sections.keys).sorted()) {
        for (t in 0 until TRACKS) {
            check(LayerField.SectionLevel(s, t))
            check(LayerField.SectionReverb(s, t))
            check(LayerField.SectionDelay(s, t))
        }
        for (m in Macro.entries) check(LayerField.SectionMacro(s, m))
        for (p in DelayFx.entries) check(LayerField.SectionDelayFx(s, p))
    }
    return out
}
