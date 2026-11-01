package org.balch.orpheus.features.pulsar.bench

import kotlin.math.floor
import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.FmPatchNames
import org.balch.orpheus.core.audio.KnobMeaning
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.SignalPath
import org.balch.orpheus.core.audio.knobMeaning
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

/** A track's two engines: EDM plays above energy 0.6, SPACE below 0.4, and the one playing holds in between. */
enum class EngineSlot(val pathKey: String) {
    EDM("engineEdm"),
    SPACE("engineSpace"),
}

/** The engine fields the Mixer turns, each with the pin that makes the authored value play over its macro. */
enum class EngineKnob(val field: String, val pinField: String, val param: EngineParam) {
    HARMONICS("harmonics", "pinHarmonics", EngineParam.HARMONICS),
    TIMBRE("timbre", "pinTimbre", EngineParam.TIMBRE),
    MORPH("morph", "pinMorph", EngineParam.MORPH),
}

fun TrackVoice.engine(slot: EngineSlot): OrpheusEngine = if (slot == EngineSlot.EDM) engineEdm else engineSpace

fun OrpheusEngine.knob(k: EngineKnob): Float = when (k) {
    EngineKnob.HARMONICS -> harmonics
    EngineKnob.TIMBRE -> timbre
    EngineKnob.MORPH -> morph
}

/** True when the engine plays the authored value: pinned by the vibe, or DX harmonics, which is always pinned. */
fun OrpheusEngine.pinned(k: EngineKnob): Boolean = when (k) {
    EngineKnob.HARMONICS -> pinHarmonics || engineId.forcePinHarmonics
    EngineKnob.TIMBRE -> pinTimbre
    EngineKnob.MORPH -> pinMorph
}

private fun OrpheusEngine.withKnob(k: EngineKnob, value: Float, pin: Boolean): OrpheusEngine = when (k) {
    EngineKnob.HARMONICS -> copy(harmonics = value, pinHarmonics = pin)
    EngineKnob.TIMBRE -> copy(timbre = value, pinTimbre = pin)
    EngineKnob.MORPH -> copy(morph = value, pinMorph = pin)
}

private fun OrpheusEngine.rawPin(k: EngineKnob): Boolean = when (k) {
    EngineKnob.HARMONICS -> pinHarmonics
    EngineKnob.TIMBRE -> pinTimbre
    EngineKnob.MORPH -> pinMorph
}

fun knobPath(track: Int, slot: EngineSlot, knob: EngineKnob) = "tracks[$track].${slot.pathKey}.${knob.field}"

fun knobPinPath(track: Int, slot: EngineSlot, knob: EngineKnob) = "tracks[$track].${slot.pathKey}.${knob.pinField}"

fun enginePath(track: Int, slot: EngineSlot): String = "tracks[$track].${slot.pathKey}.engineId"

fun lockPath(track: Int): String = "tracks[$track].slotLock"

private fun Vibe.withEngine(track: Int, slot: EngineSlot, change: (OrpheusEngine) -> OrpheusEngine): Vibe {
    val tv = tracks[track]
    val next = if (slot == EngineSlot.EDM) tv.copy(engineEdm = change(tv.engineEdm)) else tv.copy(engineSpace = change(tv.engineSpace))
    return copy(tracks = tracks.toMutableList().also { it[track] = next })
}

/**
 * [knob] on [track]'s [slot] engine at [value] and pinned, so the value plays instead of the macro. DX harmonics snaps
 * to the middle of its patch and keeps its own pin field: the engine always pins it.
 */
fun Vibe.withEngineKnob(track: Int, slot: EngineSlot, knob: EngineKnob, value: Float): Vibe = withEngine(track, slot) { e ->
    val forced = knob == EngineKnob.HARMONICS && e.engineId.forcePinHarmonics
    e.withKnob(knob, knob.snapped(e.engineId, value.coerceIn(0f, 1f)), pin = if (forced) e.pinHarmonics else true)
}

/** [knob] and its pin on [track]'s [slot] engine as [file] holds them. */
fun Vibe.withEngineKnobOf(file: Vibe, track: Int, slot: EngineSlot, knob: EngineKnob): Vibe {
    val from = file.tracks[track].engine(slot)
    return withEngine(track, slot) { it.withKnob(knob, from.knob(knob), from.rawPin(knob)) }
}

private fun OrpheusEngine.knobsOf(o: OrpheusEngine) = copy(
    engineId = o.engineId, harmonics = o.harmonics, timbre = o.timbre, morph = o.morph,
    pinHarmonics = o.pinHarmonics, pinTimbre = o.pinTimbre, pinMorph = o.pinMorph,
)

/**
 * True when [to] differs from this vibe only in its engines' ids, harmonics, timbre, morph and their pins, or a track's
 * slot lock. The engine reads the knobs and the lock every block and adopts a new id on the track's next note, so they
 * change under a playing song.
 */
fun Vibe.retunesTo(to: Vibe): Boolean {
    if (to.tracks.size != tracks.size) return false
    val tuned = to.copy(
        tracks = to.tracks.mapIndexed { i, t ->
            t.copy(
                engineEdm = t.engineEdm.knobsOf(tracks[i].engineEdm),
                engineSpace = t.engineSpace.knobsOf(tracks[i].engineSpace),
                slotLock = tracks[i].slotLock,
            )
        },
    )
    return tuned == this
}

/** The slot the engine plays at [energy], as `unit_process_pulsar` picks it: [previous] holds between 0.4 and 0.6. */
fun slotAt(energy: Float, previous: EngineSlot): EngineSlot = when {
    energy > 0.6f -> EngineSlot.EDM
    energy < 0.4f -> EngineSlot.SPACE
    else -> previous
}

/** What an unpinned knob plays before tension and solos: MOOD through moodHarmonics or moodTimbre, SPACE through spaceDecay. */
fun TrackVoice.macroValue(knob: EngineKnob, mood: Float, space: Float): Float {
    val (target, macro) = when (knob) {
        EngineKnob.HARMONICS -> macroMap.moodHarmonics to mood
        EngineKnob.TIMBRE -> macroMap.moodTimbre to mood
        EngineKnob.MORPH -> macroMap.spaceDecay to space
    }
    return target.min + macro * (target.max - target.min)
}

/** DX patches: the engine floors harmonics x 1.02 x 32 to one of 32, see [FmPatchNames]. */
private const val DX_PATCHES = 32
private const val DX_SCALE = 32f * 1.02f

/** How many values [knob] picks between on [engineId], or null for a smooth knob. */
fun knobSteps(engineId: OrpheusEngineId, knob: EngineKnob): Int? =
    if (knob == EngineKnob.HARMONICS && engineId.forcePinHarmonics) DX_PATCHES else null

/** [value] moved to the middle of its DX patch, so a later hysteresis nudge cannot land on the next one; else as it is. */
fun EngineKnob.snapped(engineId: OrpheusEngineId, value: Float): Float {
    if (knobSteps(engineId, this) == null) return value
    val patch = floor(value * DX_SCALE).toInt().coerceIn(0, DX_PATCHES - 1)
    return (patch + 0.5f) / DX_SCALE
}

/** [knob]'s meaning on [engineId] for a Pulsar track. */
fun knobMeaning(engineId: OrpheusEngineId, knob: EngineKnob): KnobMeaning =
    knobMeaning(engineId, knob.param, SignalPath.PULSAR) ?: KnobMeaning(knob.field.take(5), knob.field)

/** The value as a knob's readout writes it: a DX harmonics names its patch, anything else is its number. */
fun knobValueText(engineId: OrpheusEngineId, knob: EngineKnob, value: Float, number: (Float) -> String): String =
    if (knobSteps(engineId, knob) != null) FmPatchNames.patchNameFor(engineId, value) ?: number(value) else number(value)
