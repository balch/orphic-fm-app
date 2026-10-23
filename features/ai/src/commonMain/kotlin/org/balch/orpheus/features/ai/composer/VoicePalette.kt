package org.balch.orpheus.features.ai.composer

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.OrpheusEngineId.ADD
import org.balch.orpheus.core.audio.OrpheusEngineId.BD
import org.balch.orpheus.core.audio.OrpheusEngineId.CHD
import org.balch.orpheus.core.audio.OrpheusEngineId.CSAW
import org.balch.orpheus.core.audio.OrpheusEngineId.DX
import org.balch.orpheus.core.audio.OrpheusEngineId.DX2
import org.balch.orpheus.core.audio.OrpheusEngineId.DX3
import org.balch.orpheus.core.audio.OrpheusEngineId.ENS
import org.balch.orpheus.core.audio.OrpheusEngineId.FM
import org.balch.orpheus.core.audio.OrpheusEngineId.GRN
import org.balch.orpheus.core.audio.OrpheusEngineId.HH
import org.balch.orpheus.core.audio.OrpheusEngineId.MOD
import org.balch.orpheus.core.audio.OrpheusEngineId.MST
import org.balch.orpheus.core.audio.OrpheusEngineId.NES
import org.balch.orpheus.core.audio.OrpheusEngineId.NSE
import org.balch.orpheus.core.audio.OrpheusEngineId.PAR
import org.balch.orpheus.core.audio.OrpheusEngineId.PD
import org.balch.orpheus.core.audio.OrpheusEngineId.QUESTION_MARK
import org.balch.orpheus.core.audio.OrpheusEngineId.SD
import org.balch.orpheus.core.audio.OrpheusEngineId.SPK
import org.balch.orpheus.core.audio.OrpheusEngineId.STR
import org.balch.orpheus.core.audio.OrpheusEngineId.SWM
import org.balch.orpheus.core.audio.OrpheusEngineId.TOY
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_SINE
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_SQUARE
import org.balch.orpheus.core.audio.OrpheusEngineId.TRIPLE_TRIANGLE
import org.balch.orpheus.core.audio.OrpheusEngineId.TRN
import org.balch.orpheus.core.audio.OrpheusEngineId.VA
import org.balch.orpheus.core.audio.OrpheusEngineId.VCF
import org.balch.orpheus.core.audio.OrpheusEngineId.VOWEL_FOF
import org.balch.orpheus.core.audio.OrpheusEngineId.WSH
import org.balch.orpheus.core.audio.OrpheusEngineId.WTB
import org.balch.orpheus.features.ai.composer.Knob.HARMONICS
import org.balch.orpheus.features.ai.composer.Knob.MORPH
import org.balch.orpheus.features.ai.composer.Knob.TIMBRE
import org.balch.orpheus.features.ai.composer.Part.BASS
import org.balch.orpheus.features.ai.composer.Part.COMP
import org.balch.orpheus.features.ai.composer.Part.HAT
import org.balch.orpheus.features.ai.composer.Part.KICK
import org.balch.orpheus.features.ai.composer.Part.LEAD
import org.balch.orpheus.features.ai.composer.Part.PAD
import org.balch.orpheus.features.ai.composer.Part.PERC
import org.balch.orpheus.features.ai.composer.Part.SNARE
import org.balch.orpheus.features.ai.composer.Part.TEXTURE
import org.balch.orpheus.features.ai.composer.Register.HIGH
import org.balch.orpheus.features.ai.composer.Register.LOW
import org.balch.orpheus.features.ai.composer.Register.MID
import org.balch.orpheus.features.ai.composer.Register.TOP
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.GrainMode
import org.balch.orpheus.features.pulsar.models.GrainScatterMode
import org.balch.orpheus.features.pulsar.models.GrainVoicing
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice

/** An engine knob a palette voice may pin so the macros cannot sweep it. */
internal enum class Knob { HARMONICS, TIMBRE, MORPH }

/**
 * One castable voice: an engine in a role family, the registers it may sit in, and sane
 * harmonics/timbre/morph ranges. A one-point range is a quantized selector (a patch or an interval).
 */
internal data class PaletteVoice(
    val engine: OrpheusEngineId,
    val part: Part,
    val registers: List<Register>,
    val harmonics: ClosedFloatingPointRange<Float>,
    val timbre: ClosedFloatingPointRange<Float>,
    val morph: ClosedFloatingPointRange<Float>,
    val label: String,
    val styles: Set<String> = emptySet(),
    val weight: Float = 1f,
    val pins: Set<Knob> = emptySet(),
    /** Sings: gets the sung vibrato, pitch rise and vowel rise of Orphic Lore's Sirens. */
    val singer: Boolean = false,
)

private val NO_REGISTER = emptyList<Register>()

// Chord engines: harmonics and morph pick the two upper intervals, 0.66 a fifth, 0.753 an
// octave, 0.52 a near-unison chorus. Pinned, so no macro sweeps them into a clash.
// CHD harmonics 0.1337 is its fifth chord, the one quality that fits every scale.
internal val VOICE_PALETTE: List<PaletteVoice> = listOf(
    // ── KICK: Percussive on the kick pattern. Drum morph is decay, wide enough for a longer SPACE tail ──
    PaletteVoice(BD, KICK, NO_REGISTER, 0.40f..0.60f, 0.30f..0.60f, 0.20f..0.70f, "kick", setOf("rock", "electro", "funk", "reggae"), weight = 2f),
    PaletteVoice(MOD, KICK, NO_REGISTER, 0.20f..0.40f, 0.30f..0.50f, 0.20f..0.40f, "modal thump", setOf("ambient", "psych")),

    // ── SNARE and HAT: Percussive ──
    PaletteVoice(SD, SNARE, NO_REGISTER, 0.30f..0.60f, 0.40f..0.70f, 0.20f..0.70f, "snare", setOf("rock", "funk", "reggae", "electro")),
    PaletteVoice(HH, HAT, NO_REGISTER, 0.40f..0.70f, 0.40f..0.80f, 0.10f..0.60f, "hat", setOf("rock", "electro", "funk", "reggae")),

    // ── PERC: pitched hits, Melodic so they sit in their own register ──
    PaletteVoice(PAR, PERC, listOf(HIGH, TOP), 0.70f..1.00f, 0.40f..0.60f, 0.60f..1.00f, "particle wind", setOf("ambient", "psych")),
    PaletteVoice(NSE, PERC, listOf(HIGH, TOP), 0.30f..0.70f, 0.50f..0.80f, 0.10f..0.30f, "noise tick", setOf("electro", "rock")),
    PaletteVoice(MOD, PERC, listOf(HIGH, TOP), 0.30f..0.60f, 0.40f..0.70f, 0.30f..0.60f, "modal bell", setOf("ambient", "reggae")),
    PaletteVoice(STR, PERC, listOf(MID, HIGH), 0.30f..0.60f, 0.30f..0.60f, 0.10f..0.30f, "string pluck", setOf("reggae", "funk")),
    PaletteVoice(TOY, PERC, listOf(HIGH, TOP), 0.20f..0.80f, 0.50f..0.50f, 0.20f..0.80f, "toy clank", setOf("psych", "electro")),
    PaletteVoice(NES, PERC, listOf(HIGH, TOP), 0.20f..0.80f, 0.30f..0.70f, 0.30f..0.70f, "chip blip", setOf("electro")),

    // ── BASS: Melodic, ROOT_ONLY ──
    PaletteVoice(WSH, BASS, listOf(Register.BASS), 0.30f..0.60f, 0.30f..0.60f, 0.30f..0.60f, "waveshaped bass", setOf("rock", "funk")),
    PaletteVoice(VCF, BASS, listOf(Register.BASS), 0.30f..0.60f, 0.30f..0.60f, 0.30f..0.60f, "filter bass", setOf("rock", "electro")),
    PaletteVoice(PD, BASS, listOf(Register.BASS), 0.40f..0.60f, 0.40f..0.60f, 0.10f..0.30f, "round bass", setOf("ambient", "reggae")),
    PaletteVoice(VA, BASS, listOf(Register.BASS), 0.20f..0.50f, 0.20f..0.50f, 0.20f..0.50f, "analog bass", setOf("funk", "reggae")),
    PaletteVoice(DX, BASS, listOf(Register.BASS), 0.551f..0.551f, 0.40f..0.60f, 0.30f..0.60f, "FM bass, patch Insert 1", setOf("electro", "funk")),
    PaletteVoice(TRN, BASS, listOf(Register.BASS), 0.30f..0.60f, 0.30f..0.60f, 0.30f..0.60f, "terrain bass", setOf("psych")),

    // ── LEAD: Melodic, FOLLOW ──
    PaletteVoice(DX3, LEAD, listOf(MID, HIGH), 0.1991f..0.1991f, 0.40f..0.60f, 0.30f..0.60f, "FM pipes", setOf("ambient", "psych")),
    PaletteVoice(DX3, LEAD, listOf(MID, HIGH), 0.888f..0.888f, 0.40f..0.60f, 0.30f..0.60f, "FM synth orchestra", setOf("rock")),
    PaletteVoice(WSH, LEAD, listOf(MID, HIGH), 0.50f..0.80f, 0.40f..0.70f, 0.30f..0.60f, "waveshaped lead", setOf("rock", "funk")),
    PaletteVoice(FM, LEAD, listOf(MID, HIGH), 0.30f..0.70f, 0.30f..0.60f, 0.30f..0.60f, "two-op FM lead", setOf("electro", "funk")),
    PaletteVoice(WTB, LEAD, listOf(MID, HIGH), 0.20f..0.80f, 0.30f..0.70f, 0.30f..0.70f, "wavetable lead", setOf("electro", "psych")),
    PaletteVoice(VOWEL_FOF, LEAD, listOf(MID, HIGH), 0.20f..0.80f, 0.50f..0.50f, 0.20f..0.80f, "vowel singer", setOf("psych", "ambient", "funk"), singer = true),
    PaletteVoice(SPK, LEAD, listOf(MID), 0.1667f..0.1667f, 0.20f..0.20f, 0.00f..0.50f, "sung vowels", setOf("ambient", "psych"), pins = setOf(HARMONICS, TIMBRE, MORPH), singer = true),
    PaletteVoice(CSAW, LEAD, listOf(MID, HIGH), 0.30f..0.70f, 0.50f..0.50f, 0.30f..0.70f, "notched saw", setOf("rock", "electro")),
    PaletteVoice(QUESTION_MARK, LEAD, listOf(MID, HIGH), 0.30f..0.70f, 0.50f..0.50f, 0.30f..0.70f, "question mark", setOf("psych", "electro")),
    PaletteVoice(ADD, LEAD, listOf(MID, HIGH), 0.40f..0.80f, 0.40f..0.70f, 0.30f..0.70f, "additive organ lead", setOf("ambient", "funk")),
    PaletteVoice(TRN, LEAD, listOf(MID, HIGH), 0.30f..0.70f, 0.30f..0.70f, 0.30f..0.70f, "terrain lead", setOf("psych", "electro")),

    // ── COMP: Chordal stabs, the only part that may arpeggiate ──
    PaletteVoice(DX2, COMP, listOf(LOW, MID), 0.153f..0.153f, 0.40f..0.60f, 0.30f..0.60f, "FM e-piano, patch Mark III", setOf("funk", "reggae")),
    PaletteVoice(DX2, COMP, listOf(LOW, MID), 0.276f..0.276f, 0.40f..0.60f, 0.30f..0.60f, "FM grand piano", setOf("rock", "funk")),
    PaletteVoice(DX2, COMP, listOf(LOW, MID), 0.521f..0.521f, 0.40f..0.60f, 0.30f..0.60f, "FM marimba", setOf("reggae", "ambient")),
    PaletteVoice(DX3, COMP, listOf(LOW, MID), 0.031f..0.031f, 0.40f..0.60f, 0.30f..0.60f, "FM organ, patch Hammond", setOf("rock", "reggae", "funk", "psych")),
    PaletteVoice(CHD, COMP, listOf(LOW, MID), 0.1337f..0.1337f, 0.30f..0.60f, 0.50f..0.50f, "fifth chord", setOf("rock", "electro"), pins = setOf(HARMONICS)),
    PaletteVoice(TRIPLE_SQUARE, COMP, listOf(LOW, MID), 0.66f..0.66f, 0.50f..0.50f, 0.753f..0.753f, "square fifth and octave", setOf("rock", "electro"), pins = setOf(HARMONICS, MORPH)),
    PaletteVoice(TRIPLE_TRIANGLE, COMP, listOf(LOW, MID), 0.66f..0.66f, 0.50f..0.50f, 0.52f..0.52f, "triangle fifth, chorused", setOf("reggae", "psych"), pins = setOf(HARMONICS, MORPH)),
    PaletteVoice(VA, COMP, listOf(LOW, MID), 0.20f..0.50f, 0.20f..0.50f, 0.20f..0.50f, "analog stab", setOf("funk", "rock")),
    PaletteVoice(STR, COMP, listOf(LOW, MID), 0.40f..0.70f, 0.30f..0.60f, 0.10f..0.40f, "plucked chord", setOf("reggae", "funk")),

    // ── PAD: Chordal PAD, sustained, re-struck on each chord ──
    PaletteVoice(ENS, PAD, listOf(LOW, MID, HIGH), 0.30f..0.60f, 0.30f..0.60f, 0.20f..0.60f, "string machine", setOf("ambient", "psych", "rock")),
    PaletteVoice(STR, PAD, listOf(LOW, MID, HIGH), 0.40f..0.70f, 0.30f..0.60f, 0.10f..0.40f, "bowed string", setOf("ambient", "reggae")),
    PaletteVoice(GRN, PAD, listOf(LOW, MID, HIGH), 0.30f..0.70f, 0.30f..0.70f, 0.30f..0.70f, "grain pad", setOf("ambient", "psych")),
    PaletteVoice(ADD, PAD, listOf(LOW, MID, HIGH), 0.40f..0.80f, 0.40f..0.70f, 0.30f..0.70f, "additive choir", setOf("ambient", "electro")),
    PaletteVoice(CHD, PAD, listOf(LOW, MID, HIGH), 0.1337f..0.1337f, 0.30f..0.60f, 0.50f..0.50f, "fifth chord pad", setOf("ambient", "rock"), pins = setOf(HARMONICS)),
    PaletteVoice(TRIPLE_SINE, PAD, listOf(LOW, MID, HIGH), 0.66f..0.66f, 0.50f..0.50f, 0.753f..0.753f, "sine organ fifth", setOf("ambient", "psych"), pins = setOf(HARMONICS, MORPH)),
    PaletteVoice(TRIPLE_TRIANGLE, PAD, listOf(LOW, MID, HIGH), 0.753f..0.753f, 0.50f..0.50f, 0.52f..0.52f, "triangle octave, chorused", setOf("ambient"), pins = setOf(HARMONICS, MORPH)),
    PaletteVoice(MST, PAD, listOf(LOW, MID, HIGH), 0.20f..0.80f, 0.20f..0.80f, 0.40f..0.80f, "grain mist", setOf("ambient", "psych")),
    PaletteVoice(WTB, PAD, listOf(LOW, MID, HIGH), 0.30f..0.70f, 0.30f..0.70f, 0.30f..0.70f, "wavetable pad", setOf("electro", "psych")),
    PaletteVoice(VOWEL_FOF, PAD, listOf(LOW, MID, HIGH), 0.20f..0.60f, 0.50f..0.50f, 0.20f..0.60f, "vowel choir", setOf("psych", "ambient"), singer = true),
    PaletteVoice(DX3, PAD, listOf(LOW, MID, HIGH), 0.490f..0.490f, 0.20f..0.40f, 0.10f..0.30f, "FM pad, patch Cirrus", setOf("ambient", "electro")),
    // DeepSpace's warm organ drone (track 4); its timbre starts at VA's playability floor, 0.35.
    PaletteVoice(VA, PAD, listOf(LOW, MID), 0.15f..0.35f, 0.35f..0.45f, 0.05f..0.20f, "warm organ", setOf("ambient", "psych")),

    // ── TEXTURE: sparse Melodic colour, high and wet ──
    PaletteVoice(MOD, TEXTURE, listOf(HIGH, TOP), 0.40f..0.60f, 0.40f..0.60f, 0.40f..0.60f, "bell ping", setOf("ambient", "reggae")),
    PaletteVoice(PAR, TEXTURE, listOf(HIGH, TOP), 0.30f..0.90f, 0.40f..0.70f, 0.30f..0.90f, "particle cloud", setOf("ambient", "psych")),
    PaletteVoice(SWM, TEXTURE, listOf(HIGH, TOP), 0.30f..0.70f, 0.30f..0.70f, 0.30f..0.70f, "swarm", setOf("psych", "electro")),
    PaletteVoice(NES, TEXTURE, listOf(HIGH, TOP), 0.20f..0.80f, 0.30f..0.70f, 0.30f..0.70f, "chip arpeggio", setOf("electro")),
    PaletteVoice(TRN, TEXTURE, listOf(HIGH, TOP), 0.30f..0.70f, 0.30f..0.70f, 0.30f..0.70f, "terrain shimmer", setOf("psych")),
    PaletteVoice(SPK, TEXTURE, listOf(MID, HIGH), 0.30f..0.50f, 0.40f..0.60f, 0.25f..0.45f, "whisper", setOf("ambient", "psych")),
    PaletteVoice(MST, TEXTURE, listOf(HIGH, TOP), 0.20f..0.80f, 0.20f..0.80f, 0.10f..0.40f, "grain sparkle", setOf("ambient")),
    PaletteVoice(GRN, TEXTURE, listOf(HIGH, TOP), 0.30f..0.70f, 0.30f..0.70f, 0.30f..0.70f, "grain shimmer", setOf("ambient", "psych")),
    PaletteVoice(QUESTION_MARK, TEXTURE, listOf(HIGH, TOP), 0.30f..0.70f, 0.50f..0.50f, 0.30f..0.70f, "question mark", setOf("psych")),
    PaletteVoice(TOY, TEXTURE, listOf(HIGH, TOP), 0.20f..0.80f, 0.50f..0.50f, 0.20f..0.80f, "toy", setOf("psych", "electro")),
    PaletteVoice(NSE, TEXTURE, listOf(TOP), 0.50f..0.90f, 0.60f..0.90f, 0.10f..0.30f, "noise air", setOf("electro")),
)

/** A vetted drone bed: one or two sustained tracks placed whole from [BED_FIRST_TRACK]. */
internal data class DroneBed(val name: String, val tracks: List<TrackVoice>)

// Every bed is a Chordal PAD that re-strikes on each chord, so it follows the progression
// and the mid-energy texture notch leaves it alone.
private val BED_ROLE = TrackRole.Chordal(
    comping = ChordComping(style = CompingStyle.PAD, arpMode = ArpMode.NEVER, restrikeOnChordChange = true),
    chordFollow = ChordFollow.FOLLOW,
)

// Macro maps are the source voice's own, since its unpinned knobs play from them. Only
// energyDensity is (1, 1): a Chordal PAD would otherwise drop held chords.
private val ALL_NOTES = MacroTarget(1f, 1f)

// SpaceDrone's organ and mellotron lock their tone to fixed mood and space values.
private val SPACE_DRONE_ORGAN_MACROS = TrackMacroMap.MELODIC.copy(
    moodHarmonics = MacroTarget(0.4f, 0.4f), moodTimbre = MacroTarget(0.3f, 0.3f), spaceDecay = MacroTarget(0.25f, 0.25f),
    energyDensity = ALL_NOTES,
)
private val SPACE_DRONE_MELLOTRON_MACROS = TrackMacroMap.MELODIC.copy(
    moodHarmonics = MacroTarget(0.55f, 0.55f), moodTimbre = MacroTarget(0.4f, 0.4f), spaceDecay = MacroTarget(0.2f, 0.2f),
    energyDensity = ALL_NOTES,
)

// Engine character comes from the named shipped voice; the SPACE engine and any range moved
// (to 48 or above, or clear of the bed's other track) are the bed's own. Volumes are trimmed so
// the loudest track is 0.45, under the bass (0.8); a two-track bed keeps its source ratio.
internal val DRONE_BEDS: List<DroneBed> = listOf(
    DroneBed(
        "ensemble haze",
        listOf(
            // BellTolls track 7: the warm ensemble haze, strings below the energy line.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    ENS, volume = 0.45f,
                    modLfoRate = 0.05f, modLfoDepth = 0.4f, modLfoShape = 0.3f, modLfoCoupling = 0.2f,
                    reverbSend = 0.55f, delaySend = 0.25f, noteRangeLow = 48, noteRangeHigh = 67, reverbBrightness = 0.55f,
                ),
                engineSpace = OrpheusEngine(
                    STR, volume = 0.45f,
                    modLfoRate = 0.05f, modLfoDepth = 0.4f, modLfoShape = 0.3f, modLfoCoupling = 0.2f,
                    reverbSend = 0.55f, delaySend = 0.25f, noteRangeLow = 48, noteRangeHigh = 67, reverbBrightness = 0.55f,
                ),
                role = BED_ROLE, pan = -0.35f, density = 0.12f,
                envelopeProfile = EnvelopeProfile.EFFECT, macroMap = TrackMacroMap.EFFECT.copy(energyDensity = ALL_NOTES),
            ),
        ),
    ),
    DroneBed(
        "organ and mellotron",
        listOf(
            // SpaceDrone track 4: the ensemble standing in for an organ chord drone, moved up
            // from 48-64 to sit over the mellotron.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    ENS, volume = 0.37f, harmonics = 0.4f, timbre = 0.3f, morph = 0.25f,
                    modLfoRate = 0.02f, modLfoDepth = 0.5f, modLfoShape = 0.1f, modLfoCoupling = 0.35f,
                    reverbSend = 0.45f, delaySend = 0.3f, noteRangeLow = 57, noteRangeHigh = 69, reverbBrightness = 0.4f,
                ),
                engineSpace = OrpheusEngine(
                    STR, volume = 0.37f, harmonics = 0.4f, timbre = 0.3f, morph = 0.25f,
                    modLfoRate = 0.02f, modLfoDepth = 0.5f, modLfoShape = 0.1f, modLfoCoupling = 0.35f,
                    reverbSend = 0.45f, delaySend = 0.3f, noteRangeLow = 57, noteRangeHigh = 69, reverbBrightness = 0.4f,
                ),
                role = BED_ROLE, pan = -0.15f, density = 0.12f,
                envelopeProfile = EnvelopeProfile.DRONE, macroMap = SPACE_DRONE_ORGAN_MACROS,
            ),
            // SpaceDrone track 6: the mellotron's low strings, raised from 43-60 to clear the bass.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    STR, volume = 0.45f, harmonics = 0.55f, timbre = 0.4f, morph = 0.2f,
                    modLfoRate = 0.03f, modLfoDepth = 0.4f, modLfoShape = 0.2f, modLfoCoupling = 0.3f,
                    reverbSend = 0.5f, delaySend = 0.3f, noteRangeLow = 48, noteRangeHigh = 59, reverbBrightness = 0.45f,
                ),
                engineSpace = OrpheusEngine(
                    ENS, volume = 0.45f, harmonics = 0.55f, timbre = 0.4f, morph = 0.2f,
                    modLfoRate = 0.03f, modLfoDepth = 0.4f, modLfoShape = 0.2f, modLfoCoupling = 0.3f,
                    reverbSend = 0.5f, delaySend = 0.3f, noteRangeLow = 48, noteRangeHigh = 59, reverbBrightness = 0.45f,
                ),
                role = BED_ROLE, pan = 0.2f, density = 0.1f,
                envelopeProfile = EnvelopeProfile.DRONE, macroMap = SPACE_DRONE_MELLOTRON_MACROS,
            ),
        ),
    ),
    DroneBed(
        "singing string",
        listOf(
            // DeepSpace track 5: the resonant guitar-like string with slow glide.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    STR, volume = 0.45f, harmonics = 0.9f, timbre = 0.5f, morph = 0.1f,
                    modLfoRate = 0.04f, modLfoDepth = 0.35f, modLfoShape = 0.15f, modLfoCoupling = 0.2f,
                    reverbSend = 0.3f, delaySend = 0.3f, noteRangeLow = 49, noteRangeHigh = 67,
                    reverbBrightness = 0.55f, glideRate = 0.7f,
                ),
                engineSpace = OrpheusEngine(
                    ENS, volume = 0.45f, harmonics = 0.9f, timbre = 0.5f, morph = 0.1f,
                    modLfoRate = 0.04f, modLfoDepth = 0.35f, modLfoShape = 0.15f, modLfoCoupling = 0.2f,
                    reverbSend = 0.3f, delaySend = 0.3f, noteRangeLow = 49, noteRangeHigh = 67,
                    reverbBrightness = 0.55f, glideRate = 0.7f,
                ),
                role = BED_ROLE, pan = 0.2f, density = 0.22f,
                envelopeProfile = EnvelopeProfile.DRONE, macroMap = TrackMacroMap.MELODIC.copy(energyDensity = ALL_NOTES),
            ),
        ),
    ),
    DroneBed(
        "organ drift",
        listOf(
            // DeepSpace track 4: warm analog organ above the line, its own ensemble below.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    VA, volume = 0.45f, harmonics = 0.25f, timbre = 0.2f, morph = 0.1f,
                    modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
                    reverbSend = 0.35f, delaySend = 0.25f, noteRangeLow = 49, noteRangeHigh = 61,
                    reverbBrightness = 0.45f, glideRate = 0.6f,
                ),
                engineSpace = OrpheusEngine(
                    ENS, volume = 0.45f, harmonics = 0.25f, timbre = 0.2f, morph = 0.1f,
                    modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
                    reverbSend = 0.35f, delaySend = 0.25f, noteRangeLow = 49, noteRangeHigh = 61,
                    reverbBrightness = 0.45f, glideRate = 0.6f,
                ),
                role = BED_ROLE, pan = -0.1f, density = 0.12f,
                envelopeProfile = EnvelopeProfile.DRONE, macroMap = TrackMacroMap.MELODIC.copy(energyDensity = ALL_NOTES),
            ),
        ),
    ),
    DroneBed(
        "cirrus",
        listOf(
            // TechnoWobble track 5: the dark FM pad whose patch walks under a deep slow LFO.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    DX3, volume = 0.4f, harmonics = 0.49f, timbre = 0.25f, morph = 0.15f,
                    harmonicsModulation = 0.12f, harmonicsMacroRange = 0.3f,
                    modLfoRate = 0.04f, modLfoDepth = 0.85f, modLfoShape = 0.2f, modLfoCoupling = 0.6f,
                    reverbSend = 0.6f, delaySend = 0.25f, noteRangeLow = 48, noteRangeHigh = 62,
                    reverbBrightness = 0.35f, glideRate = 0.6f,
                ),
                engineSpace = OrpheusEngine(
                    ENS, volume = 0.35f, harmonics = 0.49f, timbre = 0.25f, morph = 0.15f,
                    modLfoRate = 0.04f, modLfoDepth = 0.85f, modLfoShape = 0.2f, modLfoCoupling = 0.6f,
                    reverbSend = 0.85f, delaySend = 0.25f, noteRangeLow = 48, noteRangeHigh = 62,
                    reverbBrightness = 0.7f, glideRate = 0.6f,
                ),
                role = BED_ROLE, pan = -0.35f, density = 0.12f,
                envelopeProfile = EnvelopeProfile.DRONE, macroMap = TrackMacroMap.EFFECT.copy(energyDensity = ALL_NOTES),
            ),
        ),
    ),
    DroneBed(
        "grain and strings",
        listOf(
            // DogHouse tracks 5 and 6: the grain texture above the line, its string pad below.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    GRN, volume = 0.3f,
                    modLfoRate = 0.15f, modLfoDepth = 0.6f, modLfoShape = 0.5f, modLfoCoupling = 0.2f,
                    reverbSend = 0.5f, delaySend = 0.3f, noteRangeLow = 57, noteRangeHigh = 72,
                    reverbBrightness = 0.7f, glideRate = 0.35f,
                ),
                engineSpace = OrpheusEngine(
                    STR, volume = 0.25f,
                    modLfoRate = 0.1f, modLfoDepth = 0.7f, modLfoShape = 0.4f, modLfoCoupling = 0.3f,
                    reverbSend = 0.6f, delaySend = 0.4f, noteRangeLow = 57, noteRangeHigh = 72,
                    reverbBrightness = 0.65f, glideRate = 0.4f,
                ),
                role = BED_ROLE, pan = -0.3f, density = 0.15f,
                envelopeProfile = EnvelopeProfile.EFFECT, macroMap = TrackMacroMap.EFFECT.copy(energyDensity = ALL_NOTES),
            ),
        ),
    ),
    DroneBed(
        "vault and choir",
        listOf(
            // AetherNatalis track 3: the chord vault, on its fifth chord so it fits any scale, its
            // range narrowed from 48-72 to sit under the choir. Morph is written as it plays (spaceDecay 0.9).
            TrackVoice(
                engineEdm = OrpheusEngine(
                    CHD, volume = 0.41f, harmonics = 0.1337f, timbre = 0.4f, morph = 0.9f, pinHarmonics = true,
                    modLfoRate = 0.03f, modLfoDepth = 0.25f, modLfoShape = 0.15f, modLfoCoupling = 0.25f,
                    reverbSend = 0.7f, delaySend = 0.25f, noteRangeLow = 48, noteRangeHigh = 62,
                    reverbBrightness = 0.55f, glideRate = 0.35f,
                ),
                engineSpace = OrpheusEngine(
                    TRIPLE_SINE, volume = 0.38f, harmonics = 0.66f, timbre = 0.5f, morph = 0.753f,
                    pinHarmonics = true, pinMorph = true,
                    modLfoRate = 0.03f, modLfoDepth = 0.25f, modLfoShape = 0.15f, modLfoCoupling = 0.25f,
                    reverbSend = 0.78f, delaySend = 0.25f, noteRangeLow = 48, noteRangeHigh = 62,
                    reverbBrightness = 0.55f, glideRate = 0.5f,
                ),
                role = BED_ROLE, pan = 0f, density = 0.3f,
                envelopeProfile = EnvelopeProfile.DRONE,
                macroMap = TrackMacroMap(
                    energyVolume = MacroTarget(0.55f, 0.90f),
                    energyDensity = ALL_NOTES,
                    complexitySwing = MacroTarget(0.0f, 0.0f),
                    complexityVariation = MacroTarget(0.05f, 0.30f),
                    spaceDecay = MacroTarget(0.90f, 0.90f),
                    // Both slots pin harmonics, so this window only restates the chord they hold.
                    moodHarmonics = MacroTarget(0.1337f, 0.1337f),
                    moodTimbre = MacroTarget(0.30f, 0.60f),
                ),
            ),
            // AetherNatalis track 1: the additive choir, with fewer partials below the line
            // (pinned, since the map would otherwise play the same partials in both slots).
            TrackVoice(
                engineEdm = OrpheusEngine(
                    ADD, volume = 0.45f, harmonics = 0.62f, timbre = 0.55f, morph = 0.5f,
                    modLfoRate = 0.05f, modLfoDepth = 0.35f, modLfoShape = 0.2f, modLfoCoupling = 0.35f,
                    reverbSend = 0.75f, delaySend = 0.5f, noteRangeLow = 60, noteRangeHigh = 84,
                    reverbBrightness = 0.7f, glideRate = 0.15f,
                ),
                engineSpace = OrpheusEngine(
                    ADD, volume = 0.39f, harmonics = 0.4f, timbre = 0.55f, morph = 0.5f, pinHarmonics = true,
                    modLfoRate = 0.05f, modLfoDepth = 0.35f, modLfoShape = 0.2f, modLfoCoupling = 0.35f,
                    reverbSend = 0.82f, delaySend = 0.58f, noteRangeLow = 60, noteRangeHigh = 84,
                    reverbBrightness = 0.7f, glideRate = 0.15f,
                ),
                role = BED_ROLE, pan = -0.3f, density = 0.45f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap(
                    energyVolume = MacroTarget(0.30f, 1.0f),
                    energyDensity = ALL_NOTES,
                    complexitySwing = MacroTarget(0.0f, 0.08f),
                    complexityVariation = MacroTarget(0.15f, 0.55f),
                    spaceDecay = MacroTarget(0.35f, 0.75f),
                    moodHarmonics = MacroTarget(0.55f, 0.95f),
                    moodTimbre = MacroTarget(0.30f, 0.65f),
                ),
            ),
        ),
    ),
    DroneBed(
        "siren strings",
        listOf(
            // SpaceDrone's mellotron sung through Orphic Lore's Sirens: their vibrato and choir
            // grains. Capped at F5 (77), above which the grains squeak.
            TrackVoice(
                engineEdm = OrpheusEngine(
                    STR, volume = 0.45f, harmonics = 0.55f, timbre = 0.4f, morph = 0.2f,
                    modLfoRate = 0.03f, modLfoDepth = 0.4f, modLfoShape = 0.2f, modLfoCoupling = 0.3f,
                    reverbSend = 0.6f, delaySend = 0.2f, noteRangeLow = 57, noteRangeHigh = 72, reverbBrightness = 0.5f,
                    vibratoDepth = 25f, vibratoRate = 5.5f,
                    grainFx = true, grainMode = GrainMode.BURST, grainScatterMode = GrainScatterMode.CHORD,
                    grainVoicing = GrainVoicing.CENTERED, grainScatter = 0.2f, grainRate = 0.6f,
                    grainRandomTiming = true, grainShape = 1f, grainSize = 0.75f, grainBlend = 0.45f,
                ),
                engineSpace = OrpheusEngine(
                    ENS, volume = 0.45f, harmonics = 0.55f, timbre = 0.4f, morph = 0.2f,
                    modLfoRate = 0.03f, modLfoDepth = 0.4f, modLfoShape = 0.2f, modLfoCoupling = 0.3f,
                    reverbSend = 0.6f, delaySend = 0.2f, noteRangeLow = 57, noteRangeHigh = 72, reverbBrightness = 0.5f,
                    vibratoDepth = 25f, vibratoRate = 5.5f,
                    grainFx = true, grainMode = GrainMode.BURST, grainScatterMode = GrainScatterMode.CHORD,
                    grainVoicing = GrainVoicing.CENTERED, grainScatter = 0.2f, grainRate = 0.6f,
                    grainRandomTiming = true, grainShape = 1f, grainSize = 0.75f, grainBlend = 0.45f,
                ),
                role = BED_ROLE, pan = 0.2f, density = 0.1f,
                envelopeProfile = EnvelopeProfile.DRONE, macroMap = SPACE_DRONE_MELLOTRON_MACROS,
            ),
        ),
    ),
)
