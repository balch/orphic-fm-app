package org.balch.orpheus.features.ai.composer

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.CastPinRecord
import org.balch.orpheus.features.pulsar.models.CastProvenance
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * BED_FIXED places a vetted drone bed and rolls every other track, drums and bass included.
 * ALL_ROLLED rolls all eight tracks; the theory rules alone keep it together.
 */
enum class CastMode { BED_FIXED, ALL_ROLLED }

/** Holds [track] to a part ([role], a [Part] name such as "pad" or "lead") and/or an EDM [engine]. */
data class CastPin(val track: Int, val role: String? = null, val engine: OrpheusEngineId? = null)

data class CastRequest(
    val mode: CastMode,
    val seed: Long,
    /** Any of rock, electro, ambient, reggae, psych, funk. Others are ignored. */
    val styleTags: Set<String> = emptySet(),
    val pins: List<CastPin> = emptyList(),
)

data class CastResult(val vibe: Vibe, val seed: Long, val mode: CastMode, val summary: List<String>)

/**
 * Rolls an 8-track vibe skeleton from [VOICE_PALETTE] and [DRONE_BEDS] under [CastRules]: engines,
 * roles, knobs, registers, envelopes, EDM/SPACE pairs and starting levels. The frame is a
 * placeholder and there is no arrangement or lick. The same request always gives the same result.
 */
object VibeCaster {

    const val PLACEHOLDER_NAME = "Untitled cast"

    private sealed interface Slot {
        data class Bed(val bed: DroneBed, val voice: TrackVoice) : Slot
        data class Rolled(val voice: PaletteVoice, val register: Register?, val pinned: Boolean) : Slot
    }

    fun cast(req: CastRequest): CastResult {
        val rnd = Random(req.seed)
        // Sorted, so an equal set of tags in any order rolls the same skeleton.
        val tags = req.styleTags.map { it.lowercase() }.filter { it in STYLE_TAGS }.distinct().sorted()
        val pins = validatePins(req.pins)
        val slots = arrayOfNulls<Slot>(8)
        val reserved = mutableListOf<IntRange>()

        val pinned = pins.entries.sortedBy { it.key }.map { (track, pin) -> track to pinnedVoice(pin, tags, rnd) }

        if (req.mode == CastMode.BED_FIXED) {
            // Only a bed that leaves every pinned voice a register of its own.
            val fitting = DRONE_BEDS.filter { bed ->
                placeAll(pinned.map { it.second }, bed.ranges().toMutableList(), rnd = null) != null
            }
            require(fitting.isNotEmpty()) { "the pinned voices leave no register for any drone bed" }
            val bed = fitting[rnd.nextInt(fitting.size)]
            val at = BED_SLOTS.filter { it !in pins }.take(bed.tracks.size)
            require(at.size == bed.tracks.size) { "pins leave no room for the drone bed '${bed.name}'" }
            at.zip(bed.tracks).forEach { (track, voice) -> slots[track] = Slot.Bed(bed, voice) }
            reserved += bed.ranges()
        }

        val placements = placeAll(pinned.map { it.second }, reserved, rnd)
            ?: throw IllegalArgumentException("the pinned voices need more registers than there are")
        pinned.zip(placements).forEach { (pin, placed) ->
            slots[pin.first] = Slot.Rolled(pin.second, placed.register, pinned = true)
        }

        for (track in 0 until 8) {
            if (slots[track] != null) continue
            var options = SLOT_PARTS[track].mapValues { (part, w) ->
                val bias = tags.fold(w) { acc, tag -> acc * (STYLE_PART_BIAS[tag]?.get(part) ?: 1f) }
                if (part == Part.PAD && req.mode == CastMode.BED_FIXED) bias * BED_PAD_DAMPING else bias
            }
            while (slots[track] == null) {
                val part = weighted(options.entries.toList(), rnd) { it.value }.key
                val voice = pickVoice(fresh(VOICE_PALETTE.filter { it.part == part }, slots), tags, rnd)
                val placed = place(voice, reserved, rnd)
                if (placed == null) options = options - part
                else slots[track] = Slot.Rolled(voice, placed.register, pinned = false)
            }
        }

        val filled = slots.map { it!! }
        var panSum = 0f
        val tracks = filled.mapIndexed { track, slot ->
            when (slot) {
                is Slot.Bed -> slot.voice
                is Slot.Rolled -> build(track, slot, tags, panSum, rnd)
            }.also { panSum += it.pan }
        }.let { chooseArpeggiator(it, rnd) }

        val vibe = Vibe(
            name = PLACEHOLDER_NAME,
            tracks = tracks,
            seed = (req.seed xor (req.seed ushr 32)).toInt().let { if (it == 0) 1 else it },
            bpm = 100f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.A,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0.15f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL,
            ),
            cast = CastProvenance(
                seed = req.seed,
                mode = req.mode.name,
                styleTags = tags,
                pins = pins.values.sortedBy { it.track }.map { CastPinRecord(it.track, it.role, it.engine) },
            ),
        )
        val bassTracks = filled.withIndex()
            .filter { (it.value as? Slot.Rolled)?.voice?.part == Part.BASS }
            .map { it.index }.toSet()
        val broken = CastRules.violations(vibe, bassTracks)
        check(broken.isEmpty()) { "cast seed ${req.seed} breaks the theory rules: $broken" }
        return CastResult(vibe, req.seed, req.mode, filled.mapIndexed { i, s -> summarize(i, s, tracks[i], vibe) })
    }

    /** Where a voice sits; [register] is null for Percussive parts, which play fixed drum notes. */
    private data class Placement(val register: Register?)

    private fun validatePins(pins: List<CastPin>): Map<Int, CastPin> {
        pins.forEach { require(it.track in 0..7) { "pin track must be 0-7, was ${it.track}" } }
        val dupes = pins.groupBy { it.track }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "tracks $dupes are pinned more than once" }
        return pins.associateBy { it.track }
    }

    private fun parsePart(role: String): Part = Part.entries.firstOrNull { it.name.equals(role.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("unknown role '$role'; use one of ${Part.entries.joinToString { it.name.lowercase() }}")

    private fun pinnedVoice(pin: CastPin, tags: List<String>, rnd: Random): PaletteVoice {
        require(pin.role != null || pin.engine != null) { "track ${pin.track}: a pin needs a role or an engine" }
        val part = pin.role?.let(::parsePart)
        val candidates = VOICE_PALETTE.filter { v ->
            (part == null || v.part == part) && (pin.engine == null || v.engine == pin.engine)
        }
        require(candidates.isNotEmpty()) {
            val engines = VOICE_PALETTE.filter { part == null || it.part == part }.map { it.engine }.distinct()
            "track ${pin.track}: ${pin.engine} is not in the palette${part?.let { " as a ${it.name.lowercase()}" } ?: ""}; " +
                "pick one of $engines"
        }
        // An engine-only pin takes a part this track normally plays, when the engine has one.
        val fitting = candidates.filter { it.part in SLOT_PARTS[pin.track] }.ifEmpty { candidates }
        return pickVoice(fitting, tags, rnd)
    }

    /** [voices] whose engine no track plays yet, or all of them when every engine is taken. */
    private fun fresh(voices: List<PaletteVoice>, slots: Array<Slot?>): List<PaletteVoice> {
        val taken = slots.mapNotNull {
            when (it) {
                is Slot.Bed -> it.voice.engineEdm.engineId
                is Slot.Rolled -> it.voice.engine
                null -> null
            }
        }.toSet()
        return voices.filter { it.engine !in taken }.ifEmpty { voices }
    }

    private fun pickVoice(voices: List<PaletteVoice>, tags: List<String>, rnd: Random): PaletteVoice =
        weighted(voices, rnd) { v -> v.weight * (if (v.styles.any { it in tags }) STYLE_VOICE_BOOST else 1f) }

    /** Places [voice], or returns null when a register-owning part finds every register taken. */
    private fun place(voice: PaletteVoice, reserved: MutableList<IntRange>, rnd: Random): Placement? {
        if (voice.registers.isEmpty()) return Placement(null)
        if (voice.part !in REGISTER_OWNERS) {
            return Placement(voice.registers[rnd.nextInt(voice.registers.size)])
        }
        val free = voice.registers.filter { fits(it.low..it.high, reserved) }
        if (free.isEmpty()) return null
        return Placement(free[rnd.nextInt(free.size)].also { reserved += it.low..it.high })
    }

    /**
     * Places all [voices] at once, backtracking when a register-owning voice is boxed in. Leaves
     * their registers in [reserved] on success. A null [rnd] tries registers in order, for a dry run.
     */
    private fun placeAll(voices: List<PaletteVoice>, reserved: MutableList<IntRange>, rnd: Random?): List<Placement>? {
        val voice = voices.firstOrNull() ?: return emptyList()
        val rest = voices.drop(1)
        if (voice.registers.isEmpty() || voice.part !in REGISTER_OWNERS) {
            val placed = place(voice, reserved, rnd ?: Random(0))!!
            return placeAll(rest, reserved, rnd)?.let { listOf(placed) + it }
        }
        val free = voice.registers.filter { fits(it.low..it.high, reserved) }
        for (register in if (rnd == null) free else free.shuffled(rnd)) {
            val range = register.low..register.high
            reserved += range
            placeAll(rest, reserved, rnd)?.let { return listOf(Placement(register)) + it }
            reserved.removeAt(reserved.lastIndexOf(range))
        }
        return null
    }

    private fun DroneBed.ranges(): List<IntRange> = tracks.map { it.engineEdm.noteRangeLow..it.engineEdm.noteRangeHigh }

    private fun fits(range: IntRange, reserved: List<IntRange>): Boolean = reserved.all {
        CastRules.shared(range.first, range.last, it.first, it.last) <= CastRules.MAX_SHARED_SEMITONES
    }

    private fun build(track: Int, slot: Slot.Rolled, tags: List<String>, panSum: Float, rnd: Random): TrackVoice {
        val v = slot.voice
        val shape = PART_SHAPES.getValue(v.part)
        val k = roll(v, rnd)
        val heldTexture = isHeldTexture(track, v.part)
        // Unpinned knobs play from the macro map, so the map is centred on the roll: the
        // MOOD and SPACE knobs still move the tone, around the rolled value.
        val macroMap = shape.macroMap.copy(moodHarmonics = window(k.h), moodTimbre = window(k.t), spaceDecay = window(k.m))
            .let { if (heldTexture) it.copy(energyDensity = PAD_MACROS.energyDensity) else it }
        val edm = sounding(engine(v, k, slot.register, shape), macroMap)
        val space = pairedSpace(v, edm, macroMap, slot.register, shape, tags, rnd)
        val pan = when {
            shape.panMax == 0f -> 0f
            else -> {
                val size = r2(shape.panMax * (0.5f + 0.5f * rnd.nextFloat()))
                val sign = when {
                    panSum > 0f -> -1f
                    panSum < 0f -> 1f
                    else -> if (rnd.nextBoolean()) 1f else -1f
                }
                sign * size
            }
        }
        return TrackVoice(
            engineEdm = edm,
            engineSpace = space,
            role = if (heldTexture) PAD_ROLE else role(v, tags, rnd),
            pan = pan,
            density = r2(shape.density * (0.8f + 0.4f * rnd.nextFloat())),
            envelopeProfile = shape.envelope,
            macroMap = macroMap,
            barStrategy = shape.barStrategy,
        )
    }

    private data class Knobs(val h: Float, val t: Float, val m: Float)

    /** How far the MOOD or SPACE knob swings a rolled knob either side of its roll. */
    private const val MACRO_WINDOW = 0.15f

    private fun window(x: Float) = MacroTarget(r2(maxOf(0f, x - MACRO_WINDOW)), r2(minOf(1f, x + MACRO_WINDOW)))

    /** [e] with each knob written as render plays it at mid macros, so the JSON reads what sounds. */
    private fun sounding(e: OrpheusEngine, macroMap: TrackMacroMap): OrpheusEngine {
        val (h, t, m) = CastRules.effective(e, macroMap)
        return e.copy(harmonics = h, timbre = t, morph = m)
    }

    private fun roll(v: PaletteVoice, rnd: Random) =
        Knobs(pick(v.playable(Knob.HARMONICS), rnd), pick(v.playable(Knob.TIMBRE), rnd), pick(v.playable(Knob.MORPH), rnd))

    private fun PaletteVoice.isPoint(knob: Knob) = playable(knob).let { it.start == it.endInclusive }

    /** The voice's range for [knob], lifted to the engine's playability floor, which render applies anyway. */
    private fun PaletteVoice.playable(knob: Knob): ClosedFloatingPointRange<Float> {
        val f = EngineMirror.floors(engine)
        val (range, floor) = when (knob) {
            Knob.HARMONICS -> harmonics to f.harmonics
            Knob.TIMBRE -> timbre to f.timbre
            Knob.MORPH -> morph to f.morph
        }
        return maxOf(range.start, floor)..maxOf(range.endInclusive, floor)
    }

    private fun pick(range: ClosedFloatingPointRange<Float>, rnd: Random): Float =
        if (range.start == range.endInclusive) range.start
        else r2(range.start + (range.endInclusive - range.start) * rnd.nextFloat())

    private fun engine(v: PaletteVoice, k: Knobs, register: Register?, shape: PartShape): OrpheusEngine {
        // Orphic Lore's Sirens: a sung waver, a slow eighth-tone climb, the vowel opening out.
        // The vowel is morph on SPK but harmonics on VOWEL_FOF, where morph is the voice type.
        val sing = v.singer
        val vowelOnHarmonics = sing && v.engine == OrpheusEngineId.VOWEL_FOF
        return OrpheusEngine(
            engineId = v.engine,
            volume = shape.volume,
            harmonics = k.h,
            timbre = k.t,
            morph = k.m,
            modLfoRate = shape.lfoRate,
            modLfoDepth = shape.lfoDepth,
            reverbSend = shape.reverbSend,
            delaySend = shape.delaySend,
            noteRangeLow = register?.low ?: 0,
            noteRangeHigh = register?.high ?: 0,
            // A one-point range is a fixed setting: pinned in either slot, never swept by a window.
            pinHarmonics = Knob.HARMONICS in v.pins || v.isPoint(Knob.HARMONICS),
            pinTimbre = Knob.TIMBRE in v.pins || v.isPoint(Knob.TIMBRE),
            pinMorph = Knob.MORPH in v.pins || v.isPoint(Knob.MORPH),
            vibratoDepth = if (sing) 25f else 0f,
            pitchRise = if (sing) 25f else 0f,
            morphRise = if (sing && !vowelOnHarmonics) 0.08f else 0f,
            morphRiseTime = if (sing && !vowelOnHarmonics) 1.5f else 2f,
            harmonicsRise = if (vowelOnHarmonics) 0.15f else 0f,
            harmonicsRiseTime = if (vowelOnHarmonics) 1.5f else 2f,
        )
    }

    /**
     * The SPACE voice, sharing the track's macro map: usually another engine of the same part,
     * else the same engine with one knob pinned [CastRules.PAIR_SHIFT] or more from what EDM
     * plays. Quantized voices always change engine.
     */
    private fun pairedSpace(
        v: PaletteVoice, edm: OrpheusEngine, macroMap: TrackMacroMap, register: Register?, shape: PartShape,
        tags: List<String>, rnd: Random,
    ): OrpheusEngine {
        val others = VOICE_PALETTE.filter { it.part == v.part && it.engine != v.engine }
            .let { o -> o.filter { register == null || register in it.registers }.ifEmpty { o } }
        val (h, t, m) = CastRules.effective(edm, macroMap)
        val shifts = listOf(
            Knob.HARMONICS to shiftTargets(v.playable(Knob.HARMONICS), h),
            Knob.TIMBRE to shiftTargets(v.playable(Knob.TIMBRE), t),
            Knob.MORPH to shiftTargets(v.playable(Knob.MORPH), m),
        ).filter { it.second.isNotEmpty() }
        if (others.isNotEmpty() && (shifts.isEmpty() || rnd.nextFloat() < 0.6f)) {
            val other = pickVoice(others, tags, rnd)
            return otherEngine(other, engine(other, roll(other, rnd), register, shape), macroMap)
        }
        check(shifts.isNotEmpty()) { "${v.engine} ${v.part} can be neither re-engined nor shifted" }
        val (knob, targets) = shifts[rnd.nextInt(shifts.size)]
        val to = pick(targets[rnd.nextInt(targets.size)], rnd)
        return when (knob) {
            Knob.HARMONICS -> edm.copy(harmonics = to, pinHarmonics = true)
            Knob.TIMBRE -> edm.copy(timbre = to, pinTimbre = true)
            Knob.MORPH -> edm.copy(morph = to, pinMorph = true)
        }
    }

    /**
     * Another engine under the EDM-centred map: a knob the map would push outside this voice's
     * range is pinned at its own roll, the rest play from the map and are written as such.
     */
    private fun otherEngine(other: PaletteVoice, rolled: OrpheusEngine, macroMap: TrackMacroMap): OrpheusEngine {
        val (h, t, m) = CastRules.effective(rolled.copy(pinHarmonics = false, pinTimbre = false, pinMorph = false), macroMap)
        val pinH = rolled.pinHarmonics || rolled.engineId.forcePinHarmonics || h !in other.playable(Knob.HARMONICS)
        val pinT = rolled.pinTimbre || t !in other.playable(Knob.TIMBRE)
        val pinM = rolled.pinMorph || m !in other.playable(Knob.MORPH)
        return rolled.copy(
            harmonics = if (pinH) rolled.harmonics else h,
            timbre = if (pinT) rolled.timbre else t,
            morph = if (pinM) rolled.morph else m,
            pinHarmonics = if (rolled.engineId.forcePinHarmonics) rolled.pinHarmonics else pinH,
            pinTimbre = pinT,
            pinMorph = pinM,
        )
    }

    /** The parts of [range] at least [CastRules.PAIR_SHIFT] from [from]. */
    private fun shiftTargets(range: ClosedFloatingPointRange<Float>, from: Float): List<ClosedFloatingPointRange<Float>> {
        val below = range.start..(from - CastRules.PAIR_SHIFT)
        val above = (from + CastRules.PAIR_SHIFT)..range.endInclusive
        return listOf(below, above).filter { it.start <= it.endInclusive }
    }

    private val PAD_ROLE = TrackRole.Chordal(
        comping = ChordComping(style = CompingStyle.PAD, arpMode = ArpMode.NEVER, restrikeOnChordChange = true),
        chordFollow = ChordFollow.FOLLOW,
    )

    /**
     * A texture on 5 or 6 is held as a Chordal PAD: the engine lifts Chordal tracks out of the
     * mid-energy notch, where a lick-less Melodic texture plays 26 dB down. Track 7 gates every role.
     */
    private fun isHeldTexture(track: Int, part: Part) =
        part == Part.TEXTURE && track >= EngineMirror.FIRST_NOTCH_TRACK && track != EngineMirror.FX_TRACK

    private fun role(v: PaletteVoice, tags: List<String>, rnd: Random): TrackRole = when (v.part) {
        Part.KICK, Part.SNARE, Part.HAT -> TrackRole.Percussive
        Part.BASS -> TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY)
        Part.PERC, Part.LEAD, Part.TEXTURE -> TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW)
        Part.PAD -> PAD_ROLE
        Part.COMP -> {
            val styles: List<CompingStyle> = tags.flatMap { STYLE_COMPING[it].orEmpty() }.ifEmpty { COMP_STYLES }
            val style = styles[rnd.nextInt(styles.size)]
            TrackRole.Chordal(
                comping = ChordComping(
                    style = style,
                    arpMode = ArpMode.NEVER,
                    humanization = CompingHumanization(
                        dropProbability = r2(0.1f + 0.2f * rnd.nextFloat()),
                        ghostProbability = if (style in GHOSTED_COMP_STYLES) r2(0.15f + 0.15f * rnd.nextFloat()) else 0f,
                    ),
                    fills = CompingFills(
                        everyNBars = if (rnd.nextBoolean()) 4 else 8,
                        fillType = COMP_FILLS[rnd.nextInt(COMP_FILLS.size)],
                        skipProbability = r2(0.2f + 0.2f * rnd.nextFloat()),
                    ),
                ),
                chordFollow = ChordFollow.FOLLOW,
            )
        }
    }

    private val COMP_FILLS = listOf(FillType.ASCENDING_ARP, FillType.DESCENDING_ARP, FillType.TURNAROUND, FillType.DROP_OUT)

    /** Half the casts let one stab track on a single-note engine arpeggiate; the rest stay NEVER. */
    private fun chooseArpeggiator(tracks: List<TrackVoice>, rnd: Random): List<TrackVoice> {
        val candidates = tracks.withIndex().filter { (_, t) ->
            val comping = (t.role as? TrackRole.Chordal)?.comping
            comping != null && comping.style != CompingStyle.PAD &&
                !CastRules.isChordEngine(t.engineEdm) && !CastRules.isChordEngine(t.engineSpace)
        }
        if (candidates.isEmpty() || !rnd.nextBoolean()) return tracks
        val (i, t) = candidates[rnd.nextInt(candidates.size)]
        val role = t.role as TrackRole.Chordal
        val arp = role.copy(
            comping = role.comping.copy(
                arpMode = ArpMode.ALWAYS,
                arpSpeed = r2(0.1f + 0.05f * rnd.nextFloat()),
                arpDirection = ArpDirection.entries[rnd.nextInt(ArpDirection.entries.size)],
            ),
        )
        return tracks.toMutableList().also { it[i] = t.copy(role = arp) }
    }

    private fun summarize(i: Int, slot: Slot, t: TrackVoice, vibe: Vibe): String {
        val e = t.engineEdm
        val s = t.engineSpace
        val what = when (slot) {
            is Slot.Bed -> "BED '${slot.bed.name}'"
            is Slot.Rolled -> "${slot.voice.part} '${slot.voice.label}'" + if (slot.pinned) " (pinned)" else ""
        }
        val roleText = when (val r = t.role) {
            TrackRole.Percussive -> "Percussive"
            is TrackRole.Melodic -> "Melodic ${r.chordFollow}"
            is TrackRole.Chordal -> "Chordal ${r.comping.style::class.simpleName}" +
                if (r.comping.arpMode != ArpMode.NEVER) " arp" else ""
        }
        val range = if (e.noteRangeHigh > 0) ", notes ${e.noteRangeLow}-${e.noteRangeHigh}" else ""
        val spaceText = if (s.engineId != e.engineId) "${s.engineId}" else "${s.engineId} ${knobText(s)}"
        return "track $i: $what, EDM ${e.engineId} ${knobText(e)} / SPACE $spaceText, $roleText, " +
            "${t.envelopeProfile}$range, vol ${e.volume}, pan ${t.pan}" + quietNote(i, t, vibe)
    }

    /** Empty when [t] is heard at the vibe's macros, else when it will be (see [EngineMirror]). */
    private fun quietNote(i: Int, t: TrackVoice, vibe: Vibe): String = when {
        EngineMirror.sounds(i, t, vibe.energy, vibe.complexity) -> ""
        i == EngineMirror.FX_TRACK ->
            "; FX slot, silent at these macros: speaks below energy 0.4, or at energy over 0.6 with complexity over 0.7"
        t.role is TrackRole.Melodic ->
            "; in the mid-energy notch: about 26 dB down at energy 0.45-0.55 until it plays the vibe's lick"
        else -> "; in the mid-energy notch: about 26 dB down at energy 0.45-0.55"
    }

    private fun knobText(e: OrpheusEngine) = "h${e.harmonics} t${e.timbre} m${e.morph}"

    private fun <T> weighted(items: List<T>, rnd: Random, weight: (T) -> Float): T {
        val total = items.sumOf { weight(it).toDouble() }
        var x = rnd.nextDouble() * total
        for (item in items) {
            x -= weight(item)
            if (x < 0) return item
        }
        return items.last()
    }

    private fun r2(x: Float): Float = (x * 100f).roundToInt() / 100f
}
