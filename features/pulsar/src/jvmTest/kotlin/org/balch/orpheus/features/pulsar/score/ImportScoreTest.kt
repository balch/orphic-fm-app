package org.balch.orpheus.features.pulsar.score

import java.io.File
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.models.NotatedScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ImportScoreTest {

    /**
     * Track 0: three monophonic quarter notes. Track 1: a dyad, so polyphony > 1.
     * Mirrors [InspectScoreTest]'s builder (Task 7) — duplicated rather than shared,
     * since that one is a private member of a different test class.
     */
    private fun sequence(): Sequence {
        val seq = Sequence(Sequence.PPQ, 480)
        val mono = seq.createTrack()
        listOf(60, 62, 64).forEachIndexed { i, p ->
            mono.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, p, 100), i * 480L))
            mono.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, p, 0), i * 480L + 240L))
        }
        val poly = seq.createTrack()
        listOf(72, 76).forEach { p ->
            poly.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 1, p, 60), 0L))
            poly.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 1, p, 0), 480L))
        }
        return seq
    }

    private fun monoMapping(transpose: Int = 0, midiTrack: Int = 0, throughTick: Int? = null) = ScoreMapping(
        name = "Test Score",
        source = "in-memory fixture, see sequence()",
        license = "Public domain -- test fixture",
        parts = listOf(
            MappedPart(
                midiTrack = midiTrack,
                trackIndex = 0,
                name = "Mono",
                transpose = transpose,
                throughTick = throughTick,
            ),
        ),
    )

    private fun polyMapping() = ScoreMapping(
        name = "Test Score",
        source = "in-memory fixture, see sequence()",
        license = "Public domain -- test fixture",
        parts = listOf(MappedPart(midiTrack = 1, trackIndex = 0, name = "Poly")),
    )

    @Test
    fun `conversion requantises to 96 PPQ`() {
        // Source is 480 PPQ; a quarter note at source tick 480 lands on score tick 96.
        val score = MidiScoreImporter.convert(sequence(), monoMapping())
        val events = score.parts.single().events
        assertEquals(listOf(0, 96, 192), events.map { it.tick })
    }

    @Test
    fun `note durations survive the requantise`() {
        // 240 source ticks is an eighth: 48 at 96 PPQ.
        val score = MidiScoreImporter.convert(sequence(), monoMapping())
        assertTrue(score.parts.single().events.all { it.durationTicks == 48 })
    }

    @Test
    fun `transpose shifts every pitch in the part`() {
        val score = MidiScoreImporter.convert(sequence(), monoMapping(transpose = -12))
        assertEquals(listOf(48, 50, 52), score.parts.single().events.map { it.pitch })
    }

    @Test
    fun `a chord keeps every note, sharing one tick`() {
        // Was the opposite: the importer used to flatten to the highest pitch. The voice
        // pool allocates a slot per note now, so discarding them would be throwing away
        // the polyphony the whole feature exists to play.
        val result = MidiScoreImporter.convertWithReport(sequence(), polyMapping())
        val events = result.score.parts.single().events
        assertEquals(listOf(72, 76), events.map { it.pitch }, "both chord tones kept, low to high")
        assertEquals(1, events.map { it.tick }.distinct().size, "a chord shares one tick")
        assertEquals(0, result.droppedZeroDuration, "nothing dropped: neither note is zero-length")
    }

    @Test
    fun `a mapping naming a missing MIDI track fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            MidiScoreImporter.convert(sequence(), monoMapping(midiTrack = 9))
        }
    }

    @Test
    fun `a part truncated at N keeps only events before N`() {
        // The three quarter notes requantise to score ticks 0, 96, 192 (see "conversion
        // requantises to 96 PPQ" above). throughTick=96 should keep only the tick-0 event.
        val score = MidiScoreImporter.convert(sequence(), monoMapping(throughTick = 96))
        assertEquals(listOf(0), score.parts.single().events.map { it.tick })
    }

    // ── ScoreExportConfig: multi-part selection, merge, hold/bandRelease painting ──

    /** Two tracks whose onsets interleave rather than fall in two contiguous runs. */
    private fun twoTrackSequence(): Sequence {
        val seq = Sequence(Sequence.PPQ, 480)
        val a = seq.createTrack()
        val b = seq.createTrack()
        listOf(0L to 60, 960L to 62).forEach { (t, p) ->
            a.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, p, 100), t))
            a.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, p, 0), t + 240L))
        }
        listOf(480L to 72, 1440L to 74).forEach { (t, p) ->
            b.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 1, p, 100), t))
            b.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 1, p, 0), t + 240L))
        }
        return seq
    }

    @Test
    fun `merged tracks interleave sorted, satisfying the non-descending invariant`() {
        // Concatenating track 0 then track 1 verbatim would read 0, 192, 96, 288 -- descending
        // at index 2. Merging must sort that back out.
        val config = ScoreExportConfig(tracks = mapOf(0 to 0, 1 to 0), merges = listOf(0 to 1))
        val score = MidiScoreImporter.convertMultiPart(twoTrackSequence(), config, "Merge Test")

        val merged = score.parts.single()
        assertEquals(listOf(0, 96, 192, 288), merged.events.map { it.tick })
        assertEquals(listOf(60, 72, 62, 74), merged.events.map { it.pitch })
    }

    @Test
    fun `a merge pair that doesn't actually share a destination fails loudly`() {
        // track 1 maps to destination 5, not 0 -- a plausible copy-paste mistake in a
        // hand-written recipe, and exactly what merges' own validation exists to catch.
        assertFailsWith<IllegalArgumentException> {
            ScoreExportConfig(tracks = mapOf(0 to 0, 1 to 5), merges = listOf(0 to 1))
        }
    }

    @Test
    fun `two source tracks selected without a merge stay two separate parts`() {
        val config = ScoreExportConfig(tracks = mapOf(0 to 3, 1 to 5))
        val score = MidiScoreImporter.convertMultiPart(twoTrackSequence(), config, "Two Parts")

        assertEquals(setOf(3, 5), score.parts.map { it.trackIndex }.toSet())
    }

    /** Two tracks that both land on score tick 96 (a tie), each with one more note outside it. */
    private fun tiedReleaseSequence(): Sequence {
        val seq = Sequence(Sequence.PPQ, 480)
        val a = seq.createTrack()
        val b = seq.createTrack()
        listOf(0L to 60, 480L to 62).forEach { (t, p) ->
            a.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, p, 100), t))
            a.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, p, 0), t + 240L))
        }
        listOf(480L to 72, 1440L to 74).forEach { (t, p) ->
            b.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 1, p, 100), t))
            b.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 1, p, 0), t + 240L))
        }
        return seq
    }

    @Test
    fun `holdRangeTicks paints hold, bandReleaseAtTick paints every tied nearest event`() {
        // dest 0: score ticks 0, 96. dest 1: score ticks 96 (ties dest 0's tick 96 exactly),
        // 288 (outside the hold range and far from the release tick -- must stay untouched).
        val config = ScoreExportConfig(
            tracks = mapOf(0 to 0, 1 to 1),
            holdRangeTicks = 0..96,
            bandReleaseAtTick = 96,
        )
        val score = MidiScoreImporter.convertMultiPart(tiedReleaseSequence(), config, "Hold Test")

        val partA = score.parts.single { it.trackIndex == 0 }
        val partB = score.parts.single { it.trackIndex == 1 }
        assertEquals(listOf(0, 96), partA.events.map { it.tick })
        assertEquals(listOf(true, true), partA.events.map { it.hold })
        assertEquals(listOf(false, true), partA.events.map { it.bandRelease }, "only tick 96 ties the release")

        assertEquals(listOf(96, 288), partB.events.map { it.tick })
        assertEquals(listOf(true, false), partB.events.map { it.hold }, "tick 288 is outside 0..96")
        assertEquals(listOf(true, false), partB.events.map { it.bandRelease }, "dest 1's own tick-96 event also ties")
    }

    @Test
    fun `convertAllUnmerged caps a track at MAX_SCORE_EVENTS and skips silent tracks`() {
        val seq = Sequence(Sequence.PPQ, 480)
        val silent = seq.createTrack()
        silent.add(MidiEvent(ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 0, 0), 0L))
        val busy = seq.createTrack()
        val over = NotatedScore.MAX_SCORE_EVENTS + 50
        for (i in 0 until over) {
            val t = i * 480L
            busy.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), t))
            busy.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), t + 240L))
        }

        val dump = MidiScoreImporter.convertAllUnmerged(seq, "Cap Test")

        assertEquals(1, dump.parts.size, "the note-free track must not produce a part")
        assertEquals(NotatedScore.MAX_SCORE_EVENTS, dump.parts.single().events.size)
    }

    @Test
    fun `the Fifth's shipped selection paints the gated opening from real MIDI ticks`() {
        val sequence = MidiSystem.getSequence(File("../../scores/source/fifth-lead.mid"))
        val config = FifthOrchestraRecipe.shipped

        val score = MidiScoreImporter.convertMultiPart(sequence, config, FifthOrchestraRecipe.ORCHESTRA_NAME)

        assertTrue(score.parts.size <= 8, "shipped selection must fit the engine's per-piece track cap")
        assertEquals(score.parts.size, score.parts.map { it.trackIndex }.toSet().size, "trackIndex must be unique")

        val opening = config.holdRangeTicks!!
        score.parts.forEach { part ->
            part.events.forEach { e ->
                assertEquals(e.tick in opening, e.hold, "part '${part.name}' tick ${e.tick}")
            }
        }

        val releaseTick = config.bandReleaseAtTick
        val released = score.parts.flatMap { p -> p.events.filter { it.bandRelease }.map { p.name to it.tick } }
        assertTrue(released.isNotEmpty(), "at least one event must carry bandRelease")
        released.forEach { (name, tick) -> assertEquals(releaseTick, tick, "$name's release must land exactly on tick $releaseTick") }
        // Strings, contrabass, and clarinet all reach the second held note in unison/octaves;
        // flute/oboe/bassoon haven't entered this early at all -- none of those should release.
        assertEquals(setOf("Violins", "Viola", "Cello", "Contrabass", "Clarinet"), released.map { it.first }.toSet())
    }
}

/**
 * Write-or-verify guard for MIDI-derived score assets, mirroring `ExportPresetsTest`
 * (`features/presets/build.gradle.kts`).
 *
 * `scores/source/` holds pairs of files: a [ScoreMapping] JSON plus a same-named `.mid`
 * file it maps (e.g. `fifth-lead.json` + `fifth-lead.mid`). For each pair found there,
 * this test converts the MIDI through [MidiScoreImporter] and syncs the result against
 * the checked-in asset at `src/commonMain/composeResources/files/scores/<name>.json` —
 * the app loads a score from there at runtime via `NotatedScoreProvider`, never from
 * `scores/source/`, so the raw MIDI (and its licensing baggage) never ships.
 *
 * A plain `./gradlew jvmTest` only **verifies** that every checked-in asset still matches
 * what converting its source produces right now, and fails loudly on drift. It never
 * writes to the working tree. Regenerate deliberately:
 * ```
 * ./gradlew :features:pulsar:importScore
 * ```
 *
 * `scores/source/` is checked in by the score-player work. Until then
 * there is nothing to convert, so this test prints why and skips rather than failing.
 */
class ImportScoreFixtureTest {

    private val sourceDir = File("../../scores/source")
    private val outDir = File("src/commonMain/composeResources/files/scores")
    private val writing = System.getProperty(WRITE_PROPERTY) == "true"
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `generated score assets match their source MIDI`() {
        val mappingFiles = sourceDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }
        if (mappingFiles.isNullOrEmpty()) {
            println(
                "ImportScoreFixtureTest: no mapping JSON under ${sourceDir.path}; skipping " +
                    "until a score is checked in.",
            )
            return
        }
        mappingFiles.forEach(::syncScore)
    }

    private fun syncScore(mappingFile: File) {
        val mapping = json.decodeFromString<ScoreMapping>(mappingFile.readText())
        val midiFile = File(sourceDir, mappingFile.nameWithoutExtension + ".mid")
        require(midiFile.isFile) {
            "ScoreMapping '${mapping.name}' (${mappingFile.name}) has no sibling MIDI file; " +
                "expected ${midiFile.path}"
        }

        val generated = json.encodeToString(
            MidiScoreImporter.convert(MidiSystem.getSequence(midiFile), mapping),
        )
        syncGenerated(generated, File(outDir, mappingFile.name), mappingFile.name)
    }

    /**
     * Task 10's multi-part exports: the shipped `fifth-orchestra` (8 written parts,
     * merges, timbres, the gated opening) and the unmerged `fifth-full` authoring dump --
     * both derived from the SAME `fifth-lead.mid` that the single-part `fifth-lead.json`
     * above already maps, via [FifthOrchestraRecipe] rather than a checked-in
     * [ScoreMapping] (see that recipe's kdoc for why). Skips, rather than failing, if the
     * source MIDI isn't present -- same reasoning as the loop above.
     */
    @Test
    fun `fifth-orchestra and fifth-full match the source MIDI`() {
        val midiFile = File(sourceDir, "fifth-lead.mid")
        if (!midiFile.isFile) {
            println(
                "ImportScoreFixtureTest: no ${midiFile.path}; skipping the Fifth's multi-part " +
                    "exports until the source MIDI is checked in.",
            )
            return
        }
        val sequence = MidiSystem.getSequence(midiFile)

        val orchestra = MidiScoreImporter.convertMultiPart(
            sequence, FifthOrchestraRecipe.shipped, FifthOrchestraRecipe.ORCHESTRA_NAME,
        )
        syncGenerated(
            json.encodeToString(orchestra),
            File(outDir, "${FifthOrchestraRecipe.ORCHESTRA_NAME}.json"),
            midiFile.name,
        )

        val full = MidiScoreImporter.convertAllUnmerged(
            sequence, FifthOrchestraRecipe.FULL_NAME, FifthOrchestraRecipe.fullTrackNames,
        )
        syncGenerated(
            json.encodeToString(full),
            File(outDir, "${FifthOrchestraRecipe.FULL_NAME}.json"),
            midiFile.name,
        )
    }

    private fun syncGenerated(generated: String, outFile: File, sourceName: String) {
        if (writing) {
            outFile.parentFile?.mkdirs()
            outFile.writeText(generated)
            println("Wrote ${outFile.path}")
            return
        }

        if (!outFile.isFile) {
            fail(
                "Generated score asset is missing: ${outFile.path}\n" +
                    "Regenerate it with: ./gradlew :features:pulsar:importScore",
            )
        }
        assertEquals(
            generated,
            outFile.readText(),
            "${outFile.name} is out of sync with its generator (source $sourceName). " +
                "This file is derived output -- fix the mapping/recipe or source MIDI, not the " +
                "JSON. Regenerate with: ./gradlew :features:pulsar:importScore",
        )
    }

    private companion object {
        const val WRITE_PROPERTY = "orpheus.score.write"
    }
}
