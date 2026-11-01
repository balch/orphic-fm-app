package org.balch.orpheus.core.audio

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

/** The three macro params every engine reads. */
enum class EngineParam { HARMONICS, TIMBRE, MORPH }

/** Where the engine plays: a Pulsar track in the DJ mixer, or a voice on the Orpheus Duo panel. */
enum class SignalPath { PULSAR, PANEL }

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
    put(ENS, Triple(m("chord"), m("chorus", "chorus / filter"), m("reg", "registration")))
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
        put(t, Triple(m("intvl", "2nd osc interval"), UNUSED, m("intvl2", "3rd osc interval")))
    }
    put(CSAW, Triple(m("width", "notch width"), UNUSED, m("depth", "notch depth")))
    put(TOY, Triple(m("decim", "sample rate"), UNUSED, m("crush", "bit depth")))
    put(VOWEL_FOF, Triple(m("vowel"), UNUSED, m("voice", "voice type")))
    put(QUESTION_MARK, Triple(m("p1", "param 1"), UNUSED, m("p2", "param 2")))
    for (c in listOf(LRZ, ROS, DUF, HEN, CHU)) put(c, Triple(m("chaos", "chaos amount"), m("rate", "chaos rate"), m("blend", "sine / chaos blend")))
    put(OSC, Triple(m("fdbk", "feedback"), m("tri/sq", "triangle to square"), m("detune", "detune, ±25 cents")))
    put(MST, Triple(m("bank", "wavetable bank"), m("pos", "table position"), m("grain", "grain length")))
}

/** The Duo panel sends each voice's env speed as SPK morph, so the word axis is unreachable there. */
private val SPK_MORPH_PANEL = KnobMeaning("-", "env speed picks the word", active = false)

private val OSC_MORPH_PANEL = m("detune", "duo detune, ±25 cents")

/** What [param] does on [id] along [path], or null when the engine cannot play there (MST on the panel). */
fun knobMeaning(id: OrpheusEngineId, param: EngineParam, path: SignalPath): KnobMeaning? {
    if (path == SignalPath.PANEL) {
        if (id == MST) return null
        if (id == SPK && param == EngineParam.MORPH) return SPK_MORPH_PANEL
        if (id == OSC && param == EngineParam.MORPH) return OSC_MORPH_PANEL
    }
    val t = MEANINGS[id] ?: return null
    return when (param) {
        EngineParam.HARMONICS -> t.first
        EngineParam.TIMBRE -> t.second
        EngineParam.MORPH -> t.third
    }
}
