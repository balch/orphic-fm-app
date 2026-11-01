package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.djapp.ai.songref.TabParserFixtures.AM_D
import org.balch.orpheus.djapp.ai.songref.TabParserFixtures.RIFF_BARS
import org.balch.orpheus.djapp.ai.songref.TabParserFixtures.RIFF_NO_BARS
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabParserTest {
    @Test fun tabLinesAreRecognised() {
        listOf("e|--0--|", "|---3---|", "D ---2---", "Eb|--3-----|").forEach { assertTrue(isTabLine(it), it) }
        listOf("Wake up slow", "--------", "Am   D", "").forEach { assertFalse(isTabLine(it), it) }
    }

    @Test fun chordShapesBecomeChordEvents() {
        val sys = findTabSystems(AM_D).single()
        assertTrue(sys.isChordal())
        assertEquals(listOf("Am" to "x02210", "D" to "xx0232"), chordEvents(sys).map { it.first.name to it.second })
    }

    @Test fun barsGiveExactRhythm() {
        val (notes, rhythm) = timeNotes(findTabSystems(RIFF_BARS))
        assertEquals(RhythmSource.BARS, rhythm)
        assertEquals(listOf(45, 48, 50, 52, 55, 57, 52), notes.map { it.midi })
        assertEquals(listOf(1f, 1f, 1f, 1f, 0.5f, 1.5f, 2f), notes.map { it.beats })
        assertEquals("hammer", notes[5].technique)
    }

    @Test fun spacingRhythmWithoutBars() {
        val (notes, rhythm) = timeNotes(findTabSystems(RIFF_NO_BARS))
        assertEquals(RhythmSource.SPACING, rhythm)
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.75f, 0.25f, 0.75f, 0.5f), notes.map { it.beats })
    }

    @Test fun bendsGhostsCapoAndTunings() {
        fun six(g: String, low: String = "E") = listOf("e|--------|", "B|--------|", "G|$g|", "D|--------|", "A|--------|", "$low|--------|")
        assertEquals(listOf(62 to null, 64 to "bend"), timeNotes(findTabSystems(six("--7b9---"))).first.map { it.midi to it.technique })
        assertTrue(timeNotes(findTabSystems(six("--(5)---"))).first.single().ghost)
        assertEquals(47, findTabSystems(RIFF_BARS, capo = 2).single().columns.first().notes.single().midi)
        assertEquals(listOf(43, 38, 33, 28), findTabSystems(listOf("G|--0-----|", "D|--------|", "A|--------|", "E|--------|")).single().tuning)
        assertEquals(38, findTabSystems(six("--------", low = "D")).single().tuning.last())
    }

    @Test fun raggedLinesAndAnnotationRowsStillFormOneSystem() {
        val ragged = listOf("PM----|", "e|-----------------|  ", "B|-----------------|", "G|---\t--------------|",
            "D|---------0---2---|", "A|-0---3-----------", "E|-----------------|   ", "    1 + 2 + 3 + 4")
        val sys = findTabSystems(ragged).single()
        assertEquals(6, sys.lineCount)
        assertEquals(listOf(45, 48, 50, 52), timeNotes(listOf(sys)).first.map { it.midi })
    }

    @Test fun melodyBecomesALickInTheKey() {
        val aDorian = SongKey(RootNote.A, ScaleType.DORIAN, "A dorian", 1f, "chords")
        val (notes, rhythm) = timeNotes(findTabSystems(RIFF_BARS))
        val m = toMelodies(notes, rhythm, aDorian, "Riff", section = 3, phraseSlot = null, firstIndex = 0).single()
        assertEquals(listOf(0, 2, 3, 4, 6, 7, 4), m.lick.steps.map { it.scaleDegree })
        assertEquals(listOf(1f, 1f, 1f, 1f, 0.5f, 1.5f, 2f), m.lick.steps.map { it.duration })
        assertEquals(0.1f, m.lick.steps[5].glideRate); assertEquals(-1f, m.lick.steps[0].glideRate)
        assertEquals(3, m.lickOctave); assertEquals(8, m.lick.loopLength); assertEquals(0, m.offScaleNotes)
        assertEquals("A2", m.notes.first().name)
    }

    private val aMinor = SongKey(RootNote.A, ScaleType.MINOR, "A minor", 1f, "notes")

    @Test fun melodiesSplitAt16BeatsOr64Steps() {
        val notes = List(70) { TimedNote(57, 0.5f) }
        val parts = toMelodies(notes, RhythmSource.BARS, aMinor, "Solo", 0, null, 0)
        assertEquals(listOf(16f, 16f, 3f), parts.map { p -> p.lick.steps.sumOf { it.duration.toDouble() }.toFloat() })
        assertTrue(parts.all { it.lick.steps.size <= 64 })
        assertEquals(listOf(16, 16, 4), parts.map { it.lick.loopLength })
        assertEquals(listOf("Solo", "Solo (part 2)", "Solo (part 3)"), parts.map { it.label }); assertEquals(listOf(0, 1, 2), parts.map { it.index })
    }

    @Test fun twentyFourBeatMelodySplitsSixteenPlusEight() {
        val notes = List(24) { TimedNote(57 + it % 3, 1f) }
        val parts = toMelodies(notes, RhythmSource.BARS, aMinor, "Solo", 0, null, 0)
        assertEquals(listOf(16, 8), parts.map { it.lick.steps.size }); assertEquals(listOf(16, 8), parts.map { it.lick.loopLength })
        assertEquals(listOf("Solo", "Solo (part 2)"), parts.map { it.label })
    }

    @Test fun aNoteLongerThanSixteenBeatsCarriesIntoTheNextPart() {
        val parts = toMelodies(listOf(TimedNote(57, 20f, "slide")), RhythmSource.BARS, aMinor, "Drone", 0, null, 0)
        assertEquals(listOf(16f, 4f), parts.map { it.lick.steps.single().duration }); assertEquals(listOf(16, 4), parts.map { it.lick.loopLength })
        assertEquals(parts[0].notes.single().midi, parts[1].notes.single().midi)
        assertEquals(0.3f, parts[0].lick.steps.single().glideRate); assertEquals(0f, parts[1].lick.steps.single().glideRate)
    }

    @Test fun noSeparatorLabelsAndALoneClosingBar() {
        val sys = findTabSystems(TabParserFixtures.TRAILING_BAR_RIFF).single()
        assertEquals(listOf(64, 59, 55, 50, 45, 40), sys.tuning); assertFalse(sys.hasInteriorBars())
        val (notes, rhythm) = timeNotes(listOf(sys))
        assertEquals(RhythmSource.SPACING, rhythm); assertEquals(listOf(55, 57, 55, 57, 55), notes.map { it.midi })
        assertTrue(findTabSystems(RIFF_BARS).single().hasInteriorBars()); assertEquals(RhythmSource.BARS, timeNotes(findTabSystems(RIFF_BARS)).second)
        assertEquals(listOf("A", "G"), chordEvents(findTabSystems(TabParserFixtures.BARRE_A_G).single()).map { it.first.name })
    }

    @Test fun shiftedStringRowMergesIntoItsChord() {
        val sys = findTabSystems(TabParserFixtures.SHIFTED_E_A_G).single()
        assertTrue(sys.isChordal())
        assertEquals(listOf("A" to "577655", "G" to "355433"), chordEvents(sys).map { it.first.name to it.second })
        // A melody keeps its exact columns: adjacent notes on different strings stay two notes.
        val melody = listOf("e------------|", "B------------|", "G------------|", "D--5---------|", "A---7--------|", "E------------|")
        assertEquals(listOf(55, 52), timeNotes(findTabSystems(melody)).first.map { it.midi })
    }

    @Test fun melodicRunAcrossStringsKeepsEveryNote() {
        // One note per column stepping e, B, G, D: merging would fuse each run into a fake chord.
        val run = listOf("e--5-----5-----|", "B---5-----5----|", "G----5-----5---|", "D-----5-----5--|", "A--------------|", "E--------------|")
        val sys = findTabSystems(run).single()
        assertFalse(sys.isChordal())
        assertEquals(List(8) { 1 }, sys.columns.map { it.notes.size })
    }

    @Test fun doubleClosingBarIsAnEdge() {
        assertFalse(findTabSystems(TabParserFixtures.TRAILING_BAR_RIFF.map { "$it|" }).single().hasInteriorBars())
        val doubled = timeNotes(findTabSystems(RIFF_BARS.map { "$it|" }))
        assertEquals(RhythmSource.BARS, doubled.second); assertEquals(timeNotes(findTabSystems(RIFF_BARS)).first, doubled.first)
    }

    @Test fun bendReleaseAndSustain() {
        assertTrue(isTabLine(TabParserFixtures.SOLO_BEND.first()))
        val (notes, rhythm) = timeNotes(findTabSystems(TabParserFixtures.SOLO_BEND))
        assertEquals(listOf(74 to null, 76 to "bend", 74 to "release", 71 to "pull"), notes.map { it.midi to it.technique })
        val g = listOf("e------------|", "B------------|", "G--9b11r9----|", "D------------|", "A------------|", "E------------|")
        assertEquals(listOf(64 to null, 66 to "bend", 64 to "release"), timeNotes(findTabSystems(g)).first.map { it.midi to it.technique })
        val m = toMelodies(notes, rhythm, SongKey(RootNote.A, ScaleType.DORIAN, "A dorian", 1f, "notes"), "Solo", 0, null, 0).single()
        assertEquals(listOf(-1f, 0.4f, 0.4f, 0.1f), m.lick.steps.map { it.glideRate })
    }

    @Test fun oddLineBreakCharactersNeverThrow() {
        listOf("e|--0\u0085--|", "e|--0\u2028--|", "\u2029|-----|").forEach { isTabLine(it) }
        findTabSystems(RIFF_BARS.map { it.replace("-|", "\u0085|") })
        findTabSystems(RIFF_BARS.map { it + "\u2028" })
    }

    @Test fun annotationRowAboveLabelledStringsIsDropped() {
        val sys = findTabSystems(listOf("PM--------------|") + RIFF_BARS).single()
        assertEquals(6, sys.lineCount)
        assertEquals(listOf(64, 59, 55, 50, 45, 40), sys.tuning)
        assertEquals(listOf(45, 48, 50, 52, 55, 57, 52), timeNotes(listOf(sys)).first.map { it.midi })
    }

    @Test fun denseBarEndBeforeAnEmptyBarKeepsPositiveDurations() {
        val rest = "-".repeat(17) + "|"
        fun line(label: String, first: String) = "$label|$first|$rest-0---------------|"
        val blank = "-".repeat(33)
        val lines = listOf(line("e", blank), line("B", blank), "G|" + "-".repeat(30) + "579|$rest-----------------|",
            line("D", blank), line("A", blank), line("E", blank))
        val notes = timeNotes(findTabSystems(lines)).first
        assertEquals(listOf(null, 60, 62, 64, null, 64), notes.map { it.midi }); assertEquals(3.75f, notes.first().beats)
        assertTrue(notes.all { it.beats >= 0.25f }, notes.toString())
    }

    @Test fun stackedSystemsSplitInTextOrder() {
        val twice = findTabSystems(RIFF_BARS + RIFF_BARS)
        assertEquals(listOf(0, 6), twice.map { it.firstLine })
        assertEquals(14, timeNotes(twice).first.size)
        val bass = listOf("G|--------|", "D|--------|", "A|--0-----|", "E|--------|")
        val mixed = findTabSystems(RIFF_BARS + bass)
        assertEquals(listOf(6, 4), mixed.map { it.lineCount })
        assertEquals(listOf(43, 38, 33, 28), mixed[1].tuning)
        val unlabelled = RIFF_BARS.map { it.substring(1) }
        assertEquals(2, findTabSystems(unlabelled + unlabelled).size)
    }

    @Test fun fiveLabelledGuitarStringsKeepGuitarTuning() {
        val sys = findTabSystems(RIFF_BARS.take(5)).single()
        assertEquals(listOf(64, 59, 55, 50, 45), sys.tuning)
        assertFalse(sys.isChordal())
        assertEquals(listOf(45, 48, 50, 52, 55, 57, 52), timeNotes(listOf(sys)).first.map { it.midi })
    }

    @Test fun barsWithoutAPadColumnTimeOneNotePerBeat() {
        val (notes, rhythm) = timeNotes(findTabSystems(TabParserFixtures.NO_PAD_MELODY))
        assertEquals(RhythmSource.BARS, rhythm); assertEquals(List(8) { 1f }, notes.map { it.beats })
        assertEquals(listOf(55, 57, 59, 60, 55, 57, 59, 60), notes.map { it.midi })
    }

    @Test fun offGridBarWidthsTimeOneNotePerBeat() {
        val (notes, rhythm) = timeNotes(findTabSystems(TabParserFixtures.WIDE_MELODY))
        assertEquals(RhythmSource.BARS, rhythm); assertEquals(List(8) { 1f }, notes.map { it.beats })
        assertEquals(listOf(55, 57, 59, 60, 55, 57, 59, 60), notes.map { it.midi })
    }

    @Test fun barsAFewColumnsOff16TimeOneNotePerBeat() {
        val (notes, rhythm) = timeNotes(findTabSystems(TabParserFixtures.MELODY_18))
        assertEquals(RhythmSource.BARS, rhythm); assertEquals(List(8) { 1f }, notes.map { it.beats })
        assertEquals(listOf(55, 57, 59, 60, 55, 57, 59, 60), notes.map { it.midi })
    }

    @Test fun pickupBeforeTheFirstNoteIsALeadingRest() {
        val notes = timeNotes(findTabSystems(TabParserFixtures.LATE_MELODY)).first
        assertEquals(listOf(null, 55, 57, 59, 60, 55, 57, 59, 60), notes.map { it.midi })
        assertEquals(listOf(0.5f) + List(7) { 1f } + 0.5f, notes.map { it.beats })
        val key = SongKey(RootNote.G, ScaleType.MAJOR, "G major", 1f, "notes")
        val lick = toMelodies(notes, RhythmSource.BARS, key, "Riff", 0, null, 0).single().lick
        assertEquals(-1, lick.steps.first().scaleDegree); assertEquals(8, lick.loopLength)
    }

    @Test fun crowdedBarTimesEighthsAndThePickupLeadsTheLoopBackIn() {
        val (notes, rhythm) = timeNotes(findTabSystems(TabParserFixtures.STAB_RIFF))
        assertEquals(RhythmSource.BARS, rhythm)
        // The stabs keep only their note on the line's strings; the column with none drops out.
        assertEquals(listOf(57, 55, 57, 55, 57, 57, 45, 55, 57, 55, 45, 52, 55), notes.map { it.midi })
        assertEquals(List(6) { 0.5f } + 1f + List(3) { 0.5f } + 5.5f + 0.5f + 0.5f, notes.map { it.beats })
    }

    @Test fun pickupIntoAFullBarLaysOverItsEnd() {
        val notes = timeNotes(findTabSystems(TabParserFixtures.PICKUP_BARS)).first
        assertEquals(listOf(45, 48, 48), notes.map { it.midi }); assertEquals(listOf(1f, 2.5f, 0.5f), notes.map { it.beats })
        val twice = timeNotes(findTabSystems(TabParserFixtures.PICKUP_BARS + TabParserFixtures.PICKUP_BARS)).first
        assertEquals(listOf(45, 48, 48, 45, 48, 48), twice.map { it.midi }); assertEquals(listOf(1f, 2.5f, 0.5f, 1f, 2.5f, 0.5f), twice.map { it.beats })
    }

    @Test fun powerChordRootsSetTheKeyNotTheirFifths() {
        val r = parseSongReference(TabParserFixtures.CHUG_IN_A.joinToString("\n"))
        assertEquals("notes", r.key!!.from); assertEquals(RootNote.A, r.key!!.rootNote, r.key!!.name)
    }

    @Test fun lowercaseLowEStaysInItsSystem() {
        val lowE = RIFF_BARS.dropLast(1) + RIFF_BARS.last().replaceFirst("E|", "e|")
        val sys = findTabSystems(lowE).single()
        assertEquals(listOf(64, 59, 55, 50, 45, 40), sys.tuning)
        assertEquals(listOf(45, 48, 50, 52, 55, 57, 52), timeNotes(listOf(sys)).first.map { it.midi })
        val stacked = findTabSystems(lowE + lowE)
        assertEquals(listOf(0, 6), stacked.map { it.firstLine }); assertTrue(stacked.all { it.lineCount == 6 })
    }
}
