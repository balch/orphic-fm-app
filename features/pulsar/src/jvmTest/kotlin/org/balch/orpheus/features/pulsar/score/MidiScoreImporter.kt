package org.balch.orpheus.features.pulsar.score

import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.NotatedPart
import org.balch.orpheus.features.pulsar.models.NotatedScore
import org.balch.orpheus.features.pulsar.models.PartTimbre
import org.balch.orpheus.features.pulsar.models.ScoreEvent

data class PartSummary(
    val midiTrack: Int,
    val name: String,
    val program: Int,
    val noteCount: Int,
    val lowPitch: Int,
    val highPitch: Int,
    val meanPolyphony: Double,
    val peakPolyphony: Int,
    val velocityStdDev: Double,
)

/** One parsed note, before requantisation. */
internal data class RawNote(val startTick: Long, val endTick: Long, val pitch: Int, val velocity: Int)

/**
 * One MIDI track routed to one Pulsar track, with an optional semitone transpose.
 *
 * [throughTick] truncates the part to events starting before that tick, in [NotatedScore.PPQ]
 * (score) ticks -- the model's own fixed 96-PPQ unit, not the source file's PPQ. Truncating in
 * the output domain means the mapping author reasons in the same unit the app plays back, and
 * the value keeps its meaning even if a future source file's PPQ differs. `null` (the default)
 * keeps the whole track.
 */
@Serializable
data class MappedPart(
    val midiTrack: Int,
    val trackIndex: Int,
    val name: String,
    val transpose: Int = 0,
    val throughTick: Int? = null,
)

/** Which MIDI tracks in a source file become which Pulsar-driven parts, and under what licence. */
@Serializable
data class ScoreMapping(
    val name: String,
    val source: String,
    val license: String,
    val parts: List<MappedPart>,
) {
    init {
        // Provenance belongs in git beside the asset. This is a paid app.
        require(license.isNotBlank()) { "ScoreMapping.license is required" }
        require(parts.isNotEmpty()) { "ScoreMapping '$name' maps no parts" }
    }
}

/** [score] plus the two numbers that say how lossy the conversion was. */
data class ConvertResult(
    val score: NotatedScore,
    val maxRequantiseErrorTicks: Int,
    /** Notes that requantised to zero duration. Chords are no longer a source of loss. */
    val droppedZeroDuration: Int,
)

/**
 * Recipe for a multi-part export: which source MIDI tracks become which Pulsar-driven
 * parts (0..7 -- [NotatedPart]'s own invariant), which pairs merge into one (both sides of
 * a pair must map to the same destination in [tracks]), each destination's sound, and where
 * the gated opening holds and releases the rest of the band. [holdRangeTicks] and
 * [bandReleaseAtTick] are in the output domain (score ticks at [NotatedScore.PPQ]), matching
 * [MappedPart.throughTick]'s convention.
 *
 * [names] has no counterpart in [ScoreMapping]: every [NotatedPart] needs a display name, and
 * a source file's own track names may carry no instrument identity (see
 * `FifthOrchestraRecipe`), so the export supplies one per destination track explicitly.
 */
data class ScoreExportConfig(
    val tracks: Map<Int, Int>,
    val merges: List<Pair<Int, Int>> = emptyList(),
    val timbres: Map<Int, PartTimbre> = emptyMap(),
    val holdRangeTicks: IntRange? = null,
    val bandReleaseAtTick: Int? = null,
    val names: Map<Int, String> = emptyMap(),
) {
    init {
        // convertMultiPart derives merges structurally from tracks' shared destinations --
        // merges itself is never read there. Validating it here is what keeps it meaningful:
        // an unmatched pair is an authoring mistake (wrong trackIndex on one side), not a no-op.
        merges.forEach { (a, b) ->
            val destA = tracks[a]
            val destB = tracks[b]
            require(destA != null && destA == destB) {
                "ScoreExportConfig.merges pairs ($a, $b) but tracks maps them to " +
                    "$destA and $destB -- a merge pair must share one destination"
            }
        }
    }
}

/**
 * One MIDI track's notes, kept whole and unmerged -- an authoring reference (Task 10 Step 2),
 * never pushed to the engine, so it is not bound by [NotatedPart]'s 0..7 destination-track
 * invariant. [midiTrack] is the source file's own track index.
 */
@Serializable
data class FullScorePart(val midiTrack: Int, val name: String, val events: List<ScoreEvent>)

/** The whole piece, unmerged: one [FullScorePart] per note-bearing source MIDI track. */
@Serializable
data class FullScoreDump(val name: String, val ppq: Int = NotatedScore.PPQ, val parts: List<FullScorePart>)

object MidiScoreImporter {

    private const val TRACK_NAME_META = 0x03

    fun inspect(path: String): List<PartSummary> = inspect(MidiSystem.getSequence(File(path)))

    fun inspect(sequence: Sequence): List<PartSummary> =
        sequence.tracks.mapIndexed { index, _ -> summarise(sequence, index) }

    /**
     * Most notes sounding at once across [midiTracks] combined — the number that sizes a
     * voice pool, and one no per-part figure gives you, since it depends on whether the
     * parts' peaks align. Defaults to every track.
     *
     * [releaseQuarters] holds a voice past note-off for that fraction of a quarter note.
     * The 0.0 default is the floor, not the working number: a pool that reallocates at
     * note-off cuts release tails.
     */
    fun peakEnsemblePolyphony(
        sequence: Sequence,
        midiTracks: List<Int> = sequence.tracks.indices.toList(),
        releaseQuarters: Double = 0.0,
    ): Int {
        val notes = midiTracks.flatMap { readNotes(sequence, it) }
        return peakOverlap(notes, Math.round(releaseQuarters * sequence.resolution))
    }

    /**
     * Sweep line. Releases sort before onsets at the same tick: a voice freed exactly as
     * the next note starts is reusable, and counting both would overstate by one per
     * legato hand-off.
     */
    private fun peakOverlap(notes: List<RawNote>, releaseTicks: Long): Int {
        if (notes.isEmpty()) return 0
        val deltas = ArrayList<Pair<Long, Int>>(notes.size * 2)
        notes.forEach {
            deltas += it.startTick to 1
            deltas += (it.endTick + releaseTicks) to -1
        }
        deltas.sortWith(compareBy({ it.first }, { it.second }))
        var current = 0
        var peak = 0
        deltas.forEach { (_, delta) ->
            current += delta
            if (current > peak) peak = current
        }
        return peak
    }

    fun convert(sequence: Sequence, mapping: ScoreMapping): NotatedScore =
        convertWithReport(sequence, mapping).score

    /**
     * Converts [sequence] to a [NotatedScore] per [mapping], requantising from the source
     * file's PPQ to [NotatedScore.PPQ]. Chords are kept: notes sharing an onset become
     * events sharing a tick, which the voice pool allocates a slot each. Only notes that
     * round to zero duration are dropped, and they are counted rather than discarded
     * silently — that is the failure that surfaces weeks later as "why does that sound thin".
     */
    fun convertWithReport(sequence: Sequence, mapping: ScoreMapping): ConvertResult {
        var worstError = 0
        var dropped = 0

        val parts = mapping.parts.map { mapped ->
            require(mapped.midiTrack in sequence.tracks.indices) {
                "Mapping names MIDI track ${mapped.midiTrack}, but the file has " +
                    "${sequence.tracks.size} (0..${sequence.tracks.size - 1})"
            }
            val scale = NotatedScore.PPQ.toDouble() / sequence.resolution.toDouble()

            fun quantise(tick: Long): Int {
                val exact = tick * scale
                val rounded = Math.round(exact).toInt()
                val err = Math.abs(exact - rounded)
                if (err > worstError) worstError = Math.ceil(err).toInt()
                return rounded
            }

            val byTick = readNotes(sequence, mapped.midiTrack).groupBy { quantise(it.startTick) }
            // Truncation is a deliberate authoring boundary, not lossy conversion -- events
            // it excludes are not counted in `dropped`.
            val inRange = mapped.throughTick
                ?.let { limit -> byTick.filterKeys { it < limit } }
                ?: byTick
            // Every note in a chord is emitted, sorted low-to-high within the tick so the
            // order is deterministic across runs (HashMap grouping order is not).
            val events = inRange.toSortedMap().flatMap { (tick, group) ->
                group.sortedBy { it.pitch }.mapNotNull { note ->
                    val duration = quantise(note.endTick) - tick
                    // A note shorter than one tick after requantise cannot be represented;
                    // durationTicks = 0 would fail the model's own invariant.
                    if (duration <= 0) { dropped++; null }
                    else ScoreEvent(
                        tick = tick,
                        durationTicks = duration,
                        pitch = (note.pitch + mapped.transpose).coerceIn(0, 127),
                        velocity = note.velocity,
                    )
                }
            }
            NotatedPart(trackIndex = mapped.trackIndex, name = mapped.name, events = events)
        }

        return ConvertResult(
            score = NotatedScore(name = mapping.name, parts = parts),
            maxRequantiseErrorTicks = worstError,
            droppedZeroDuration = dropped,
        )
    }

    /**
     * Requantised, tick-sorted events for one source MIDI track in the model's own domain.
     * A track's own private helper -- see [convertWithReport] for why chords sharing a tick
     * are kept rather than flattened; the same reasoning applies here.
     */
    private fun quantisedEvents(sequence: Sequence, midiTrack: Int): List<ScoreEvent> {
        val scale = NotatedScore.PPQ.toDouble() / sequence.resolution.toDouble()
        fun quantise(tick: Long) = Math.round(tick * scale).toInt()
        return readNotes(sequence, midiTrack)
            .groupBy { quantise(it.startTick) }
            .toSortedMap()
            .flatMap { (tick, group) ->
                group.sortedBy { it.pitch }.mapNotNull { note ->
                    val duration = quantise(note.endTick) - tick
                    if (duration <= 0) null
                    else ScoreEvent(tick = tick, durationTicks = duration, pitch = note.pitch, velocity = note.velocity)
                }
            }
    }

    /**
     * Multi-part export: [config] selects and merges source tracks into a shipped
     * [NotatedScore], then paints the gated opening (see [paintHolds]). A merged
     * destination's sources interleave by concatenation and a full sort on tick (ties
     * broken by pitch, matching a same-track chord's ordering) -- simpler than a literal
     * merge-of-two-sorted-lists and just as correct, since a full sort trivially satisfies
     * [NotatedPart]'s non-descending invariant regardless of input order.
     */
    fun convertMultiPart(sequence: Sequence, config: ScoreExportConfig, name: String): NotatedScore {
        val sourcesByDestination = config.tracks.entries.groupBy({ it.value }, { it.key })
        val parts = sourcesByDestination.map { (dest, sources) ->
            val events = sources.flatMap { quantisedEvents(sequence, it) }
                .sortedWith(compareBy({ it.tick }, { it.pitch }))
            NotatedPart(
                trackIndex = dest,
                name = config.names[dest] ?: "track $dest",
                events = events,
                timbre = config.timbres[dest] ?: PartTimbre(),
            )
        }
        return NotatedScore(name = name, parts = paintHolds(parts, config))
    }

    /**
     * [ScoreExportConfig.holdRangeTicks] flags every event (any part) whose tick falls
     * inside it. [ScoreExportConfig.bandReleaseAtTick] then flags whichever event(s) --
     * across every part, not just held ones -- land closest to that tick; ties all get the
     * flag, since unison instruments sharing the release note is exactly the moment the
     * whole ensemble should let go together. A part with nothing near the target simply
     * never wins the tie, so an instrument that hasn't entered yet can't pick up a stray
     * release flag just for being the "least far away".
     */
    private fun paintHolds(parts: List<NotatedPart>, config: ScoreExportConfig): List<NotatedPart> {
        val range = config.holdRangeTicks
        val held = if (range == null) parts else parts.map { p ->
            p.copy(events = p.events.map { if (it.tick in range) it.copy(hold = true) else it })
        }
        val target = config.bandReleaseAtTick ?: return held
        val minDistance = held.flatMap { it.events }.minOfOrNull { abs(it.tick - target) } ?: return held
        return held.map { p ->
            p.copy(events = p.events.map { if (abs(it.tick - target) == minDistance) it.copy(bandRelease = true) else it })
        }
    }

    /**
     * Every note-bearing MIDI track as its own unmerged part -- the orchestration
     * authoring asset (Task 10 Step 2). Never pushed to the engine: parts share no
     * destination-track namespace (there is none), so [NotatedScore.MAX_SCORE_EVENTS] is
     * truncated per part rather than validated the way [NotatedPart] would.
     */
    fun convertAllUnmerged(sequence: Sequence, name: String, trackNames: Map<Int, String> = emptyMap()): FullScoreDump {
        val parts = sequence.tracks.indices.mapNotNull { midiTrack ->
            val events = quantisedEvents(sequence, midiTrack)
            if (events.isEmpty()) null
            else FullScorePart(
                midiTrack = midiTrack,
                name = trackNames[midiTrack] ?: "track $midiTrack",
                events = events.take(NotatedScore.MAX_SCORE_EVENTS),
            )
        }
        return FullScoreDump(name = name, parts = parts)
    }

    private fun summarise(sequence: Sequence, midiTrack: Int): PartSummary {
        val notes = readNotes(sequence, midiTrack)
        val track = sequence.tracks[midiTrack]
        var name = "track $midiTrack"
        var program = -1
        for (i in 0 until track.size()) {
            val m = track[i].message
            if (m is MetaMessage && m.type == TRACK_NAME_META) {
                name = String(m.data).trim().ifBlank { name }
            }
            if (m is ShortMessage && m.command == ShortMessage.PROGRAM_CHANGE && program < 0) {
                program = m.data1
            }
        }
        if (notes.isEmpty()) {
            return PartSummary(midiTrack, name, program, 0, 0, 0, 0.0, 0, 0.0)
        }
        val velocities = notes.map { it.velocity.toDouble() }
        val mean = velocities.average()
        val sd = sqrt(velocities.sumOf { (it - mean) * (it - mean) } / velocities.size)
        // Note-weighted: for each note, how many notes sound at its onset. A monophonic
        // part scores exactly 1.0; a part of dyads scores 2.0.
        val poly = notes.map { n ->
            notes.count { it.startTick <= n.startTick && it.endTick > n.startTick }.toDouble()
        }.average()
        return PartSummary(
            midiTrack = midiTrack,
            name = name,
            program = program,
            noteCount = notes.size,
            lowPitch = notes.minOf { it.pitch },
            highPitch = notes.maxOf { it.pitch },
            meanPolyphony = poly,
            // Strict: the structural minimum. The mean understates badly — a part
            // averaging 1.87 can hit 4.
            peakPolyphony = peakOverlap(notes, 0L),
            velocityStdDev = sd,
        )
    }

    /**
     * Pairs note-on with note-off. A NOTE_ON with velocity 0 IS a note-off — the running-status
     * idiom most files use. Treating it as an onset makes every note infinite and every part
     * look massively polyphonic, which is the single easiest way to get this wrong.
     */
    internal fun readNotes(sequence: Sequence, midiTrack: Int): List<RawNote> {
        val track = sequence.tracks[midiTrack]
        val open = HashMap<Int, MutableList<Pair<Long, Int>>>()
        val out = mutableListOf<RawNote>()
        for (i in 0 until track.size()) {
            val event = track[i]
            val m = event.message as? ShortMessage ?: continue
            val isOn = m.command == ShortMessage.NOTE_ON && m.data2 > 0
            val isOff = m.command == ShortMessage.NOTE_OFF ||
                (m.command == ShortMessage.NOTE_ON && m.data2 == 0)
            when {
                isOn -> open.getOrPut(m.data1) { mutableListOf() }.add(event.tick to m.data2)
                isOff -> open[m.data1]?.removeFirstOrNull()?.let { (start, vel) ->
                    if (event.tick > start) out += RawNote(start, event.tick, m.data1, vel)
                }
            }
        }
        return out.sortedBy { it.startTick }
    }
}

/**
 * The Fifth's own multi-part selection, merges, sound, and gated-opening ticks -- the
 * provenance a `ScoreMapping` JSON would carry, kept here instead because
 * [ScoreExportConfig.holdRangeTicks] is an [IntRange] and does not round-trip through
 * kotlinx.serialization without a custom serializer.
 *
 * Track identity, since `scores/source/fifth-lead.mid`'s own track-name metadata is a
 * LilyPond staff label ("one:".."twelve:"), not an instrument name: cross-referencing GM
 * program change, pitch range, and polyphony (`./gradlew :features:pulsar:inspectScore
 * -Pmidi=.../fifth-lead.mid`) places the twelve note-bearing tracks (track 0 is a silent
 * control/tempo track) as Flute(1) Oboe(2) Clarinet(3) Bassoon(4) Horn/Trumpet(5,6)
 * Timpani(7) Violin I(8) Violin II(9) Viola(10) Cello(11) Contrabass(12).
 *
 * Shipped selection (`fifth-orchestra`, 8 parts, fits the engine's per-piece track cap): the
 * woodwind quartet plus the string section, violins merged (tracks 8/9 share GM program 40
 * "Violin" -- a genuinely doubled pair, unlike 5/6 which merely share an unrelated program
 * by MIDI-export coincidence). Horns/trumpet and timpani are dropped rather than merged:
 * neither carries independent melody this early (5/6 first sound at score tick 3312, deep
 * in the recapitulation; timpani never leaves a 5-semitone range). Contrabass stays solo --
 * it is the line `fifth-lead`/`FifthSymphonyScore` already ships, so the piece keeps its
 * signature carrier once both scores exist side by side.
 *
 * The gated opening, verified note-for-note against the MIDI (ticks below are post-
 * requantise, PPQ 96 -- see [MidiScoreImporter.quantisedEvents]): the famous eight-note
 * statement (G-G-G-Eb, then F-F-F-D a step lower) sounds at tick 48/96/144 (three G's), 192
 * (Eb, the first held note, duration 192), 432/480/528 (three F's), 576 (D, the second held
 * note, duration 384) -- in violin, viola, cello, contrabass AND clarinet (which doubles the
 * strings at written pitch here), all five in unison or octaves (contrabass doubles viola's
 * own octave). `holdRangeTicks = 48..576` flags every one of those onsets across whichever
 * parts carry them; flute, oboe, and bassoon don't enter this early, so they are untouched.
 * `bandReleaseAtTick = 576` matches the second held note's onset exactly (distance 0) in all
 * five of those parts, so exactly they -- the ones that actually reach it -- pick up
 * `bandRelease` together, the moment the whole ensemble lets the fermata go.
 */
internal object FifthOrchestraRecipe {
    const val ORCHESTRA_NAME = "fifth-orchestra"
    const val FULL_NAME = "fifth-full"

    private const val FLUTE = 0
    private const val OBOE = 1
    private const val CLARINET = 2
    private const val BASSOON = 3
    private const val CONTRABASS = 4
    private const val VIOLIN = 5
    private const val VIOLA = 6
    private const val CELLO = 7

    val shipped = ScoreExportConfig(
        tracks = mapOf(
            1 to FLUTE, 2 to OBOE, 3 to CLARINET, 4 to BASSOON,
            12 to CONTRABASS, 8 to VIOLIN, 9 to VIOLIN, 10 to VIOLA, 11 to CELLO,
        ),
        merges = listOf(8 to 9),
        names = mapOf(
            FLUTE to "Flute", OBOE to "Oboe", CLARINET to "Clarinet", BASSOON to "Bassoon",
            CONTRABASS to "Contrabass", VIOLIN to "Violins", VIOLA to "Viola", CELLO to "Cello",
        ),
        // Register-appropriate models, untransposed -- unlike fifth-lead's lone +24 contrabass
        // hack, every part here sounds in its own real octave alongside real section-mates.
        // engineIndex is OrpheusEngineId.id (core:audio), spelled as a raw int per entry below.
        timbres = mapOf(
            FLUTE to PartTimbre(
                engineIndex = 10, // FM -- low modulation index for a pure, breathy tone
                harmonics = 0.2f, timbre = 0.3f, morph = 0.3f, decay = 0.5f,
                level = 0.85f, colorResponse = 0.3f,
            ),
            OBOE to PartTimbre(
                engineIndex = 9, // WSH (waveshaping) -- reedy, brighter edge
                harmonics = 0.5f, timbre = 0.5f, morph = 0.45f, decay = 0.5f,
                level = 0.8f, colorResponse = 0.3f,
            ),
            CLARINET to PartTimbre(
                engineIndex = 8, // VA -- pushed toward square/pulse, the clarinet's odd-harmonic hollowness
                harmonics = 0.35f, timbre = 0.5f, morph = 0.3f, decay = 0.55f,
                level = 0.85f, colorResponse = 0.3f,
            ),
            BASSOON to PartTimbre(
                engineIndex = 20, // MOD (modal) -- woody, buzzy low resonance
                harmonics = 0.35f, timbre = 0.5f, morph = 0.6f, decay = 0.6f,
                level = 0.9f, colorResponse = 0.3f,
            ),
            CONTRABASS to PartTimbre(
                engineIndex = 19, // STR -- darkest, longest decay of the string family below
                harmonics = 0.3f, timbre = 0.35f, morph = 0.55f, decay = 0.75f,
                level = 1.0f, colorResponse = 0.4f,
            ),
            VIOLIN to PartTimbre(
                engineIndex = 19, // STR
                harmonics = 0.45f, timbre = 0.5f, morph = 0.4f, decay = 0.65f,
                level = 0.95f, colorResponse = 0.4f,
            ),
            VIOLA to PartTimbre(
                engineIndex = 19, // STR
                harmonics = 0.45f, timbre = 0.5f, morph = 0.45f, decay = 0.65f,
                level = 0.9f, colorResponse = 0.3f,
            ),
            CELLO to PartTimbre(
                engineIndex = 19, // STR
                harmonics = 0.35f, timbre = 0.45f, morph = 0.5f, decay = 0.7f,
                level = 0.95f, colorResponse = 0.3f,
            ),
        ),
        holdRangeTicks = 48..576,
        bandReleaseAtTick = 576,
    )

    /** Display names for the Step 2 authoring dump -- inferred identity, not the file's own. */
    val fullTrackNames = mapOf(
        1 to "Flute", 2 to "Oboe", 3 to "Clarinet", 4 to "Bassoon",
        5 to "Horn/Trumpet A", 6 to "Horn/Trumpet B", 7 to "Timpani",
        8 to "Violin I", 9 to "Violin II", 10 to "Viola", 11 to "Cello", 12 to "Contrabass",
    )
}
