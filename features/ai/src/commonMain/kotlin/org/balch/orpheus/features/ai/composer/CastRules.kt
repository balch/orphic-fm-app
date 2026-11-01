package org.balch.orpheus.features.ai.composer

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.ai.composer.Part.BASS
import org.balch.orpheus.features.ai.composer.Part.COMP
import org.balch.orpheus.features.ai.composer.Part.HAT
import org.balch.orpheus.features.ai.composer.Part.KICK
import org.balch.orpheus.features.ai.composer.Part.LEAD
import org.balch.orpheus.features.ai.composer.Part.PAD
import org.balch.orpheus.features.ai.composer.Part.PERC
import org.balch.orpheus.features.ai.composer.Part.SNARE
import org.balch.orpheus.features.ai.composer.Part.TEXTURE
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.chordFollow
import kotlin.math.abs

/** What a cast track plays. KICK, SNARE and HAT are Percussive; the rest are pitched. */
internal enum class Part { KICK, SNARE, HAT, PERC, BASS, LEAD, COMP, PAD, TEXTURE }

/**
 * Register slots as MIDI note ranges, about an octave each. Neighbours share one semitone,
 * inside [CastRules.MAX_SHARED_SEMITONES]. Only the bass goes below 48.
 */
internal enum class Register(val low: Int, val high: Int) {
    BASS(28, 47),
    LOW(48, 59),
    MID(58, 69),
    HIGH(68, 79),
    TOP(78, 91),
}

/** A part's starting mix and behaviour. Volume, density and sends are starting points for the mix pass. */
internal data class PartShape(
    val volume: Float,
    val density: Float,
    val panMax: Float,
    val reverbSend: Float,
    val delaySend: Float,
    val lfoRate: Float,
    val lfoDepth: Float,
    val envelope: EnvelopeProfile,
    val macroMap: TrackMacroMap,
    val barStrategy: BarStrategy,
)

/** EFFECT with every note firing: the plain EFFECT map drops about half of a pad's notes. */
internal val PAD_MACROS = TrackMacroMap.EFFECT.copy(energyDensity = MacroTarget(1f, 1f))

internal val PART_SHAPES: Map<Part, PartShape> = mapOf(
    KICK to PartShape(0.85f, 0.45f, 0f, 0f, 0f, 0.2f, 0f, EnvelopeProfile.RHYTHM, TrackMacroMap.RHYTHM, BarStrategy.REPEAT),
    SNARE to PartShape(0.6f, 0.35f, 0.1f, 0.1f, 0.05f, 0.2f, 0f, EnvelopeProfile.RHYTHM, TrackMacroMap.RHYTHM, BarStrategy.FILL),
    HAT to PartShape(0.5f, 0.55f, 0.2f, 0.05f, 0.1f, 0.2f, 0f, EnvelopeProfile.RHYTHM, TrackMacroMap.RHYTHM, BarStrategy.MUTATE),
    PERC to PartShape(0.4f, 0.25f, 0.4f, 0.3f, 0.25f, 0.2f, 0f, EnvelopeProfile.RHYTHM, TrackMacroMap.RHYTHM, BarStrategy.MUTATE),
    BASS to PartShape(0.8f, 0.4f, 0f, 0.05f, 0f, 0.2f, 0f, EnvelopeProfile.MELODIC, TrackMacroMap.MELODIC, BarStrategy.REPEAT),
    LEAD to PartShape(0.55f, 0.35f, 0f, 0.3f, 0.25f, 0.2f, 0f, EnvelopeProfile.MELODIC, TrackMacroMap.MELODIC, BarStrategy.MUTATE),
    COMP to PartShape(0.45f, 0.5f, 0.3f, 0.2f, 0.15f, 0.2f, 0f, EnvelopeProfile.MELODIC, TrackMacroMap.MELODIC, BarStrategy.REPEAT),
    PAD to PartShape(0.35f, 0.15f, 0.5f, 0.5f, 0.25f, 0.04f, 0.4f, EnvelopeProfile.DRONE, PAD_MACROS, BarStrategy.INDEPENDENT),
    TEXTURE to PartShape(0.3f, 0.12f, 0.6f, 0.55f, 0.4f, 0.07f, 0.5f, EnvelopeProfile.EFFECT, TrackMacroMap.EFFECT, BarStrategy.INDEPENDENT),
)

/**
 * Which parts each track may roll, with weights. The engine ties some behaviour to the index:
 * Percussive 0/1/2 play kick/snare/hat patterns, 3 is the bass bus, 7 is silent at default macros and speaks only at the energy extremes.
 */
internal val SLOT_PARTS: List<Map<Part, Float>> = listOf(
    mapOf(KICK to 0.7f, PERC to 0.18f, PAD to 0.12f),
    mapOf(SNARE to 0.5f, PERC to 0.35f, TEXTURE to 0.15f),
    mapOf(HAT to 0.6f, PERC to 0.25f, TEXTURE to 0.15f),
    mapOf(BASS to 1f),
    mapOf(LEAD to 0.55f, COMP to 0.45f),
    mapOf(PAD to 0.45f, COMP to 0.3f, TEXTURE to 0.25f),
    mapOf(PAD to 0.3f, COMP to 0.25f, TEXTURE to 0.45f),
    mapOf(TEXTURE to 0.7f, PERC to 0.3f),
)

/** Where a bed lands: the first free tracks in this order. Never 7, which is silent at default macros. */
internal val BED_SLOTS = listOf(5, 6, 4, 1, 2, 0)
internal const val BED_FIRST_TRACK = 5

/** With a bed in place, other pads roll this much less often. */
internal const val BED_PAD_DAMPING = 0.3f

internal val STYLE_TAGS = setOf("rock", "electro", "ambient", "reggae", "psych", "funk")

/** Styles built on a one-bar loop, so a cast with only these tags keeps stepCount 16. */
internal val LOOP_STYLES = setOf("electro")

/**
 * The cast's stepCount, by the kind of vibe; first match wins. A Fill lick plays stepCount / 4
 * beats, so a melody line wants 64 (16 beats) and a loop groove keeps 16.
 */
internal fun castStepCount(tags: List<String>, leadLine: Boolean?, hasLead: Boolean): Int = when {
    leadLine == true -> 64
    "ambient" in tags -> 64
    leadLine == null && hasLead -> 64
    tags.isNotEmpty() && tags.all { it in LOOP_STYLES } -> 16
    else -> 32
}

/** A palette voice tagged with a requested style weighs this much more. */
internal const val STYLE_VOICE_BOOST = 3f

internal val STYLE_PART_BIAS: Map<String, Map<Part, Float>> = mapOf(
    "rock" to mapOf(KICK to 1.5f, SNARE to 1.5f, COMP to 1.3f, LEAD to 1.3f),
    "electro" to mapOf(KICK to 1.6f, HAT to 1.4f, PERC to 1.3f, TEXTURE to 1.2f),
    "ambient" to mapOf(KICK to 0.4f, SNARE to 0.4f, HAT to 0.5f, PAD to 1.8f, TEXTURE to 1.5f),
    "reggae" to mapOf(COMP to 2f, KICK to 1.3f, SNARE to 1.3f),
    "psych" to mapOf(TEXTURE to 1.5f, PAD to 1.3f, LEAD to 1.3f),
    "funk" to mapOf(COMP to 1.6f, SNARE to 1.4f, PERC to 1.3f, KICK to 1.3f),
)

internal val COMP_STYLES: List<CompingStyle> = listOf(
    CompingStyle.ROCK_DOWNBEATS, CompingStyle.FUNK_STABS, CompingStyle.SKA_UPSTROKES,
    CompingStyle.REGGAE_SKANK, CompingStyle.BLUES_SHUFFLE, CompingStyle.JAZZ_COMP, CompingStyle.GOSPEL_STABS,
)

internal val STYLE_COMPING: Map<String, List<CompingStyle>> = mapOf(
    "rock" to listOf(CompingStyle.ROCK_DOWNBEATS, CompingStyle.BLUES_SHUFFLE),
    "electro" to listOf(CompingStyle.FUNK_STABS, CompingStyle.ROCK_DOWNBEATS),
    "reggae" to listOf(CompingStyle.REGGAE_SKANK, CompingStyle.SKA_UPSTROKES),
    "psych" to listOf(CompingStyle.JAZZ_COMP, CompingStyle.ROCK_DOWNBEATS),
    "funk" to listOf(CompingStyle.FUNK_STABS, CompingStyle.GOSPEL_STABS),
)

/** Parts that reserve their register: the sustained ones and the lead. */
internal val REGISTER_OWNERS = setOf(PAD, TEXTURE, LEAD)

/** Busy styles take ghost stabs; on sparse ones the rests are the part, so they get none. */
internal val GHOSTED_COMP_STYLES: Set<CompingStyle> = setOf(CompingStyle.FUNK_STABS, CompingStyle.GOSPEL_STABS)

/** The theory rules every cast must pass. Each returns what broke, empty when it holds. */
internal object CastRules {
    /** No voice but the bass goes under this, keeping 40-120 Hz for the kick and bass. */
    const val NON_BASS_FLOOR = 48

    /** EDM and SPACE on one engine must play at least this far apart on harmonics, timbre or morph. */
    const val MIN_PAIR_DELTA = 0.15f

    /** The caster shifts a same-engine SPACE voice this far, so rounding keeps it past [MIN_PAIR_DELTA]. */
    const val PAIR_SHIFT = 0.2f

    /** Sustained and lead voices may share at most this many semitones of note range. */
    const val MAX_SHARED_SEMITONES = 2

    /** The macro state rules judge a sound at: energy, mood and space all at 0.5. */
    const val REFERENCE_MACRO = 0.5f

    /** Semitones two ranges share; zero or less when they only touch or are apart. */
    fun shared(aLow: Int, aHigh: Int, bLow: Int, bHigh: Int): Int = minOf(aHigh, bHigh) - maxOf(aLow, bLow)

    fun shared(a: OrpheusEngine, b: OrpheusEngine): Int = shared(a.noteRangeLow, a.noteRangeHigh, b.noteRangeLow, b.noteRangeHigh)

    /**
     * Harmonics, timbre and morph as render plays them at [REFERENCE_MACRO]: a pinned knob as
     * written, else the macro map's lerp (`lerp_macro` in orpheus_unit_pulsar.cpp), then lifted to
     * the engine's playability floor. DX harmonics is always pinned.
     */
    fun effective(e: OrpheusEngine, mm: TrackMacroMap): List<Float> {
        fun lerp(t: MacroTarget) = t.min + REFERENCE_MACRO * (t.max - t.min)
        val floor = EngineMirror.floors(e.engineId)
        return listOf(
            maxOf(floor.harmonics, if (e.pinHarmonics || e.engineId.forcePinHarmonics) e.harmonics else lerp(mm.moodHarmonics)),
            maxOf(floor.timbre, if (e.pinTimbre) e.timbre else lerp(mm.moodTimbre)),
            maxOf(floor.morph, if (e.pinMorph) e.morph else lerp(mm.spaceDecay)),
        )
    }

    fun isSustained(t: TrackVoice): Boolean = t.role !is TrackRole.Percussive && (
        t.envelopeProfile == EnvelopeProfile.DRONE || t.envelopeProfile == EnvelopeProfile.EFFECT ||
            (t.role as? TrackRole.Chordal)?.comping?.style == CompingStyle.PAD
        )

    /** A lead: a Melodic line on a MELODIC envelope that follows the chords. It keeps clear of the drones. */
    fun isLead(t: TrackVoice): Boolean =
        (t.role as? TrackRole.Melodic)?.chordFollow == ChordFollow.FOLLOW && t.envelopeProfile == EnvelopeProfile.MELODIC

    /** Tracks whose register no other such track may share beyond [MAX_SHARED_SEMITONES]. */
    fun ownsRegister(t: TrackVoice): Boolean = isSustained(t) || isLead(t)

    /** A different engine, or the same engine playing a knob at least [MIN_PAIR_DELTA] away. */
    fun clearlyPaired(t: TrackVoice): Boolean {
        val edm = t.engineEdm
        val space = t.engineSpace
        if (edm == space) return false
        if (edm.engineId != space.engineId) return true
        val delta = effective(edm, t.macroMap).zip(effective(space, t.macroMap)).maxOf { (a, b) -> abs(a - b) }
        return delta >= MIN_PAIR_DELTA
    }

    /** True for engines that play a chord from one note, which an arpeggio would smear. */
    fun isChordEngine(e: OrpheusEngine): Boolean = e.engineId == OrpheusEngineId.CHD || e.engineId.isChordEngine

    /** Every broken rule in [v]; [bassTracks] are the tracks cast as the bass. */
    fun violations(v: Vibe, bassTracks: Set<Int>): List<String> =
        registerClashes(v) + bassOffRoot(v, bassTracks) + extraArpeggiators(v) + padsNotFollowing(v) +
            unpairedVoices(v) + missingRanges(v, bassTracks)

    private fun registerClashes(v: Vibe): List<String> {
        val owners = v.tracks.withIndex().filter { ownsRegister(it.value) }
        return owners.flatMapIndexed { n, (i, a) ->
            owners.drop(n + 1).mapNotNull { (j, b) ->
                val most = listOf(a.engineEdm, a.engineSpace)
                    .maxOf { x -> listOf(b.engineEdm, b.engineSpace).maxOf { y -> shared(x, y) } }
                if (most > MAX_SHARED_SEMITONES) "sustained or lead tracks $i and $j share $most semitones of register" else null
            }
        }
    }

    private fun bassOffRoot(v: Vibe, bassTracks: Set<Int>): List<String> = bassTracks
        .filterNot { t -> (v.tracks[t].role as? TrackRole.Melodic)?.chordFollow == ChordFollow.ROOT_ONLY }
        .map { "bass track $it must be Melodic with chordFollow ROOT_ONLY" }

    private fun extraArpeggiators(v: Vibe): List<String> {
        val arps = v.tracks.withIndex()
            .filter { (it.value.role as? TrackRole.Chordal)?.comping?.arpMode.let { m -> m != null && m != ArpMode.NEVER } }
            .map { it.index }
        val chordArps = arps.filter { i -> isChordEngine(v.tracks[i].engineEdm) || isChordEngine(v.tracks[i].engineSpace) }
        return (if (arps.size <= 1) emptyList() else listOf("tracks $arps all arpeggiate; at most one may")) +
            chordArps.map { "track $it arpeggiates a chord engine" }
    }

    private fun padsNotFollowing(v: Vibe): List<String> = v.tracks.withIndex().mapNotNull { (i, t) ->
        val comping = (t.role as? TrackRole.Chordal)?.comping
        val isPad = comping?.style == CompingStyle.PAD
        val isDrone = t.role !is TrackRole.Percussive && t.envelopeProfile == EnvelopeProfile.DRONE
        when {
            !isPad && !isDrone -> null
            t.chordFollow != ChordFollow.FOLLOW -> "pad track $i must follow the chords"
            t.envelopeProfile != EnvelopeProfile.DRONE && t.envelopeProfile != EnvelopeProfile.EFFECT ->
                "pad track $i must sustain"
            comping != null && (!comping.restrikeOnChordChange || comping.arpMode != ArpMode.NEVER) ->
                "pad track $i must re-strike on chord changes and never arpeggiate"
            else -> null
        }
    }

    private fun unpairedVoices(v: Vibe): List<String> = v.tracks.withIndex()
        .filterNot { clearlyPaired(it.value) }
        .map { "track ${it.index} EDM and SPACE voices play alike" }

    private fun missingRanges(v: Vibe, bassTracks: Set<Int>): List<String> = v.tracks.withIndex()
        .filter { (i, t) -> i !in bassTracks && t.role !is TrackRole.Percussive }
        .filterNot { (_, t) ->
            listOf(t.engineEdm, t.engineSpace).all { it.noteRangeLow >= NON_BASS_FLOOR && it.noteRangeHigh > it.noteRangeLow }
        }
        .map { "track ${it.index} needs its own note range at $NON_BASS_FLOOR or above" }
}
