package org.balch.orpheus.features.pulsar.bench

import kotlin.math.floor
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.OrpheusEngineId.ADD
import org.balch.orpheus.core.audio.OrpheusEngineId.BD
import org.balch.orpheus.core.audio.OrpheusEngineId.CHD
import org.balch.orpheus.core.audio.OrpheusEngineId.CHU
import org.balch.orpheus.core.audio.OrpheusEngineId.CSAW
import org.balch.orpheus.core.audio.OrpheusEngineId.DUF
import org.balch.orpheus.core.audio.OrpheusEngineId.DX
import org.balch.orpheus.core.audio.OrpheusEngineId.DX2
import org.balch.orpheus.core.audio.OrpheusEngineId.DX3
import org.balch.orpheus.core.audio.OrpheusEngineId.ENS
import org.balch.orpheus.core.audio.OrpheusEngineId.FM
import org.balch.orpheus.core.audio.OrpheusEngineId.FM_DRUM
import org.balch.orpheus.core.audio.OrpheusEngineId.GRN
import org.balch.orpheus.core.audio.OrpheusEngineId.HEN
import org.balch.orpheus.core.audio.OrpheusEngineId.HH
import org.balch.orpheus.core.audio.OrpheusEngineId.LRZ
import org.balch.orpheus.core.audio.OrpheusEngineId.MOD
import org.balch.orpheus.core.audio.OrpheusEngineId.MST
import org.balch.orpheus.core.audio.OrpheusEngineId.NES
import org.balch.orpheus.core.audio.OrpheusEngineId.NSE
import org.balch.orpheus.core.audio.OrpheusEngineId.OSC
import org.balch.orpheus.core.audio.OrpheusEngineId.PAR
import org.balch.orpheus.core.audio.OrpheusEngineId.PD
import org.balch.orpheus.core.audio.OrpheusEngineId.QUESTION_MARK
import org.balch.orpheus.core.audio.OrpheusEngineId.ROS
import org.balch.orpheus.core.audio.OrpheusEngineId.SD
import org.balch.orpheus.core.audio.OrpheusEngineId.SPK
import org.balch.orpheus.core.audio.OrpheusEngineId.STR
import org.balch.orpheus.core.audio.OrpheusEngineId.SWM
import org.balch.orpheus.core.audio.OrpheusEngineId.TOY
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_RING_MOD
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_SAW
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_SINE
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_SQUARE
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_TRIANGLE
import org.balch.orpheus.core.audio.OrpheusEngineId.TRN
import org.balch.orpheus.core.audio.OrpheusEngineId.VA
import org.balch.orpheus.core.audio.OrpheusEngineId.VCF
import org.balch.orpheus.core.audio.OrpheusEngineId.VOWEL_FOF
import org.balch.orpheus.core.audio.OrpheusEngineId.WSH
import org.balch.orpheus.core.audio.OrpheusEngineId.WTB
import org.balch.orpheus.features.pulsar.FmPatchNames
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

/** A track's two engines: EDM plays above energy 0.6, SPACE below 0.4, and the one playing holds in between. */
enum class EngineSlot(val pathKey: String) {
    EDM("engineEdm"),
    SPACE("engineSpace"),
}

/** The engine fields the Mixer turns, each with the pin that makes the authored value play over its macro. */
enum class EngineKnob(val field: String, val pinField: String) {
    HARMONICS("harmonics", "pinHarmonics"),
    TIMBRE("timbre", "pinTimbre"),
    MORPH("morph", "pinMorph"),
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

/** A knob's meaning on an engine: [short] fits under the knob, [long] reads while it is held. */
data class KnobMeaning(val short: String, val long: String, val active: Boolean = true)

private fun m(short: String, long: String = short) = KnobMeaning(short, long)

private val UNUSED = KnobMeaning("-", "not used on this engine", active = false)

// Harmonics, timbre and morph per engine, from the Plaits and Braids manuals. Braids has two macro
// params: harmonics and morph reach them and timbre is dropped (orpheus_unit_braids.cpp).
private val MEANINGS: Map<OrpheusEngineId, Triple<KnobMeaning, KnobMeaning, KnobMeaning>> = buildMap {
    put(VCF, Triple(m("reso", "resonance"), m("cutoff"), m("shape", "waveshape")))
    put(PD, Triple(m("ratio", "distortion ratio"), m("amount", "distortion amount"), m("shape", "distortion shape")))
    for (dx in listOf(DX, DX2, DX3)) put(dx, Triple(m("patch"), m("level", "modulator level"), m("env", "envelope stretch")))
    put(TRN, Triple(m("terrain"), m("radius", "path radius"), m("offset", "path offset")))
    put(ENS, Triple(m("chord", "registration"), m("chorus", "chorus / filter"), m("wave", "waveform")))
    put(NES, Triple(m("chord"), m("arp", "arpeggio / inversion"), m("pw", "pulse width")))
    put(VA, Triple(m("detune"), m("square", "square shape"), m("saw", "saw shape")))
    put(WSH, Triple(m("wave", "waveshaper"), m("fold", "wavefolder"), m("asym", "asymmetry")))
    put(FM, Triple(m("ratio", "frequency ratio"), m("index", "modulation index"), m("fdbk", "feedback")))
    put(GRN, Triple(m("ratio", "formant ratio"), m("formant", "formant frequency"), m("width", "formant width")))
    put(ADD, Triple(m("bumps", "number of bumps"), m("peak", "prominent harmonic"), m("shape", "bump shape")))
    put(WTB, Triple(m("bank"), m("row"), m("column")))
    put(CHD, Triple(m("chord", "chord type"), m("invert", "inversion"), m("wave", "waveform")))
    put(SPK, Triple(m("model", "synthesis model"), m("voice", "species / formant shift"), m("word", "phoneme / word")))
    put(SWM, Triple(m("random", "pitch randomness"), m("density", "grain density"), m("grain", "grain length")))
    put(NSE, Triple(m("filter", "filter response"), m("clock", "clock rate"), m("reso", "resonance")))
    put(PAR, Triple(m("random", "frequency randomness"), m("density", "particle density"), m("filter", "filter type")))
    put(STR, Triple(m("inharm", "inharmonicity"), m("bright", "excitation brightness"), m("decay")))
    put(MOD, Triple(m("material"), m("bright", "excitation brightness"), m("decay")))
    for (bd in listOf(BD, FM_DRUM)) put(bd, Triple(m("punch", "attack / drive"), m("tone", "brightness"), m("decay")))
    put(SD, Triple(m("noise", "tone / noise balance"), m("modes", "drum modes"), m("decay")))
    put(HH, Triple(m("metal", "metal / noise balance"), m("hpf", "high-pass cutoff"), m("decay")))
    for (t in listOf(TRIPLE_SAW, TRIPLE_SQUARE, TRIPLE_TRIANGLE, TRIPLE_SINE, TRIPLE_RING_MOD)) {
        put(t, Triple(m("intvl", "chord interval"), UNUSED, m("detune")))
    }
    put(CSAW, Triple(m("width", "notch width"), UNUSED, m("depth", "notch depth")))
    put(TOY, Triple(m("decim", "sample rate"), UNUSED, m("crush", "bit depth")))
    put(VOWEL_FOF, Triple(m("vowel"), UNUSED, m("voice", "voice type")))
    put(QUESTION_MARK, Triple(m("p1", "param 1"), UNUSED, m("p2", "param 2")))
    for (c in listOf(LRZ, ROS, DUF, HEN, CHU)) put(c, Triple(m("chaos", "chaos amount"), m("rate", "chaos rate"), m("blend", "sine / chaos blend")))
    put(OSC, Triple(m("harm", "harmonics"), m("timbre"), m("fm", "FM depth")))
    put(MST, Triple(m("bank", "wavetable bank"), m("pos", "table position"), m("grain", "grain length")))
}

fun knobMeaning(engineId: OrpheusEngineId, knob: EngineKnob): KnobMeaning {
    val t = MEANINGS[engineId] ?: return m(knob.field.take(5), knob.field)
    return when (knob) {
        EngineKnob.HARMONICS -> t.first
        EngineKnob.TIMBRE -> t.second
        EngineKnob.MORPH -> t.third
    }
}

/** The value as a knob's readout writes it: a DX harmonics names its patch, anything else is its number. */
fun knobValueText(engineId: OrpheusEngineId, knob: EngineKnob, value: Float, number: (Float) -> String): String =
    if (knobSteps(engineId, knob) != null) FmPatchNames.patchNameFor(engineId, value) ?: number(value) else number(value)
