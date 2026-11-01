package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongReferenceParserTest {
    @Test fun ultimateGuitarPageParses() {
        val r = parseSongReference(SyntheticSong.ugPage())
        assertTrue(r.ok, r.message)
        assertEquals(SongSource("ultimate-guitar", "Kettle Morning", "The Teapots", r.source!!.lines), r.source)
        assertEquals(RootNote.A to ScaleType.DORIAN, r.key!!.rootNote to r.key!!.scaleType); assertEquals("page+chords", r.key!!.from)
        assertEquals(listOf("Am", "D", "C", "G"), r.chords.map { it.name })
        assertEquals(listOf(0, 3, 2, 6), r.chords.map { it.degree }); assertEquals(listOf(4, 4, 1, 1), r.chords.map { it.count })
        assertEquals("x02210", r.chords[0].shape); assertEquals("xx0232", r.chords[1].shape); assertNull(r.chords[2].shape)
        assertEquals(listOf("Intro", "Verse 1", "Chorus", "Riff", "Chorus"), r.sections.map { it.name })
        assertEquals(listOf(SectionKind.INTRO, SectionKind.VERSE, SectionKind.CHORUS, SectionKind.RIFF, SectionKind.CHORUS), r.sections.map { it.kind })
        assertEquals(2, r.sections[2].repeats); assertEquals(2, r.sections[4].sameAs)
        assertEquals(listOf("C", "G", "Am", "D"), r.sections[2].chords); assertEquals(listOf(2, 6, 0, 3), r.sections[2].degrees)
        assertEquals(listOf("Wake up slow and put the kettle on", "Steam is rising, morning almost gone", "Pour another cup for me", "Pour another cup, let it be"), r.phraseSlots.map { it.text })
        assertEquals(listOf(9, 9, 7, 8), r.phraseSlots.map { it.syllables })
        assertEquals(listOf(listOf("Am", "D"), listOf("Am", "D"), listOf("C", "G"), listOf("Am", "D")), r.phraseSlots.map { it.chords })
        assertEquals(listOf(0, 1), r.sections[1].phraseSlots)
        r.melodies.forEach { assertTrue(it.lick.loopLength <= 16 && it.lick.steps.sumOf { s -> s.duration.toDouble() } <= 16.0001) }
        val m = r.melodies.single()
        assertEquals(3, m.section); assertEquals("Riff", m.label); assertEquals(RhythmSource.BARS, m.rhythm)
        assertEquals(listOf(0, 2, 3, 4, 6, 7, 4), m.lick.steps.map { it.scaleDegree }); assertEquals(listOf(0), r.sections[3].melodies)
        assertEquals(PluckGrid.EIGHTH_OFFBEATS, r.sections[0].pluck?.grid); assertNull(r.sections[1].pluck)
    }

    @Test fun plainTextTabParses() {
        val r = parseSongReference(SyntheticSong.PLAIN)
        assertEquals("plain", r.source!!.format); assertEquals("chords+notes", r.key!!.from)
        assertEquals(RootNote.A to ScaleType.DORIAN, r.key!!.rootNote to r.key!!.scaleType)
        assertEquals(listOf(9, 9, 7, 8), r.phraseSlots.map { it.syllables })
        assertEquals(RhythmSource.SPACING, r.melodies.single().rhythm)
        assertEquals(listOf(0, 2, 3, 4, 6, 7, 4), r.melodies.single().lick.steps.map { it.scaleDegree })
    }

    @Test fun requestedKeyRewritesDegrees() {
        val r = parseSongReference(SyntheticSong.ugPage(), RootNote.C, ScaleType.MAJOR)
        assertEquals("request", r.key!!.from)
        assertEquals(listOf(5, 1, 0, 4), r.chords.map { it.degree })
        assertEquals(listOf(5, 7, 8, 9, 11, 12, 9), r.melodies.single().lick.steps.map { it.scaleDegree })
    }

    @Test fun capoTransposesToConcertPitch() {
        val r = parseSongReference(SyntheticSong.ugPage(tonality = null, capo = 2))
        assertEquals(RootNote.B to ScaleType.DORIAN, r.key!!.rootNote to r.key!!.scaleType)
        assertEquals(listOf("Bm", "E", "D", "A"), r.chords.map { it.name }); assertTrue(r.warnings.any { "capo" in it.lowercase() })
    }

    @Test fun ugPageWithNoiseStillParses() {
        val r = parseSongReference(SyntheticSong.ugPage(noise = true))
        assertTrue(r.ok); assertEquals("Kettle Morning", r.source!!.title); assertEquals(4, r.phraseSlots.size)
    }

    @Test fun riffOnlyPasteGivesOneSectionAndAMelody() {
        val r = parseSongReference(TabParserFixtures.RIFF_BARS.joinToString("\n"))
        assertEquals(listOf("Song"), r.sections.map { it.name }); assertEquals(1, r.melodies.size); assertEquals("notes", r.key!!.from)
    }

    @Test fun inlineChordProChordsSplitFromTheLyric() {
        val r = parseSongReference("[Verse]\n[Am]Wake up [D]slow and put the kettle on")
        assertEquals("Wake up slow and put the kettle on", r.phraseSlots.single().text)
        assertEquals(listOf("Am", "D"), r.phraseSlots.single().chords); assertEquals(listOf("Am", "D"), r.chords.map { it.name })
    }

    @Test fun hugePasteIsCappedWithAWarning() {
        val big = SyntheticSong.ugPage(content = List(200) { SyntheticSong.UG_CONTENT }.joinToString("\r\n"))
        val started = System.nanoTime()
        val r = parseSongReference(big)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertTrue(r.melodies.size <= 24); assertTrue(r.phraseSlots.size <= 96); assertTrue(r.sections.size <= 64); assertTrue(r.warnings.isNotEmpty())
    }

    @Test fun nothingMusicalIsRefused() {
        val r = parseSongReference("just some words here\nand more"); assertFalse(r.ok)
    }

    @Test fun repeatChorusLineMakesASameAsSection() {
        val r = parseSongReference("[Chorus]\nC   G\nPour another cup for me\n[Verse]\nAm  D\nWake up slow\nRepeat chorus x2")
        assertEquals(listOf("Chorus", "Verse", "Chorus"), r.sections.map { it.name })
        assertEquals(0, r.sections[2].sameAs); assertEquals(2, r.sections[2].repeats)
        assertEquals("3 sections, 4 chords, 2 phrase slots, 0 melodies in ${r.key!!.name}", r.message)
    }

    @Test fun messageSummarisesTheSong() {
        assertEquals("5 sections, 4 chords, 4 phrase slots, 1 melody in A dorian", parseSongReference(SyntheticSong.ugPage()).message)
    }

    @Test fun labelLineChordsCount() {
        val r = parseSongReference("Intro: Am  G  F  E\n\nVerse 2: Am D\nWake up slow\n[Chorus] C G\n[N.C.]\nN.C.")
        assertEquals(listOf("Intro", "Verse 2", "Chorus"), r.sections.map { it.name })
        assertEquals(listOf(listOf("Am", "G", "F", "E"), listOf("Am", "D"), listOf("C", "G")), r.sections.map { it.chords })
        // The label's chords sit above the lyric under it, as a chord line would.
        assertEquals(listOf(listOf("Am", "D")), r.phraseSlots.map { it.chords })
    }

    @Test fun longLyricSlotsAreCappedAndProseLinesDropped() {
        val long = "Wake up slow and put the kettle on, steam is rising and the morning almost gone, pour another cup for me and let it be, oh"
        assertTrue(long.length in 121..160)
        val prose = "the kettle hums along while morning light comes in ".repeat(40)
        val r = parseSongReference("[Verse]\nAm D\n$long\n$prose\nPour another cup for me")
        assertEquals(2, r.phraseSlots.size)
        val slot = r.phraseSlots.first()
        assertTrue(slot.text.length <= 120, slot.text); assertTrue(long.startsWith(slot.text))
        assertEquals(countSyllables(slot.text), slot.syllables)
    }

    @Test fun noChordMarkersOpenNoSection() {
        val r = parseSongReference("[N.C.]\nN.C.\n[Verse]\nAm D\nWake up slow")
        assertEquals(listOf("Verse"), r.sections.map { it.name })
    }

    @Test fun capoIgnoresThePageKey() {
        val r = parseSongReference(SyntheticSong.ugPage(tonality = "Am", capo = 2))
        assertEquals(RootNote.B to ScaleType.DORIAN, r.key!!.rootNote to r.key!!.scaleType); assertEquals("chords+notes", r.key!!.from)
        assertTrue(r.warnings.any { "page key" in it })
    }

    @Test fun plainCapoLineTransposes() {
        listOf("Capo 2", "Capo: 2", "Capo on 2nd fret", "capo 2nd fret").forEach { capoLine ->
            val r = parseSongReference(capoLine + "\n" + SyntheticSong.PLAIN)
            assertEquals(RootNote.B to ScaleType.DORIAN, r.key!!.rootNote to r.key!!.scaleType, capoLine)
            assertEquals(listOf("Bm", "E", "D", "A"), r.chords.map { it.name }, capoLine)
            assertEquals(2, r.source!!.capo); assertTrue(r.warnings.any { "capo 2" in it })
        }
        assertEquals(3, parseSongReference("capo 3rd fret\n" + SyntheticSong.PLAIN).source!!.capo)
    }

    @Test fun inlineChordsDoNotTipALyricIntoMeta() {
        assertEquals(listOf("F#m7", "C#7"), parseSongReference("[Verse]\n[F#m7]Oh [C#7]no").phraseSlots.single().chords)
    }

    @Test fun nonBreakingSpacesSeparateChords() {
        assertEquals(listOf("Am", "D"), parseSongReference("[Verse]\nAm\u00A0\u00A0D\nWake up slow").phraseSlots.single().chords)
    }

    @Test fun melodyCapTruncatesTheLastKeptMelody() {
        val r = parseSongReference(List(30) { "[Riff]\n" + TabParserFixtures.RIFF_BARS.joinToString("\n") }.joinToString("\n\n"))
        assertEquals(24, r.melodies.size); assertTrue(r.melodies.last().truncated); assertFalse(r.melodies[22].truncated)
        assertTrue(r.warnings.any { "24 melodies" in it }); assertEquals(30, r.sections.size)
    }

    @Test fun phraseSlotCapKeeps96() {
        val r = parseSongReference("[Verse]\nAm D\n" + List(120) { "Wake up slow line $it" }.joinToString("\n"))
        assertEquals(96, r.phraseSlots.size); assertEquals(95, r.phraseSlots.last().index); assertTrue(r.warnings.any { "96" in it })
    }

    @Test fun cueStylePageKeepsCuesAndDropsProse() {
        val content = (
            listOf("The Teapots - Kettle Morning", "Tuning: standard", "", "[tab]INTRO/OUTRO:", "Figure 2c: (Play 3 times)") + TabParserFixtures.BARRE_A_G +
                listOf("[/tab]", "", "[tab]CHORUS:", "\"Wake up slow and put the kettle on...\"") + TabParserFixtures.BARRE_A_G + listOf("[/tab]", "") +
                listOf("[tab]\"Steam is rising...\"") + TabParserFixtures.BARRE_A_G + listOf("[/tab]", "") +
                listOf("[tab]\"Pour another cup...\"") + TabParserFixtures.BARRE_A_G + listOf("[/tab]", "") +
                listOf("NEXT VERSE:", "Hum along with figure 2c twice, then let the", "kettle sing alone.", "") +
                listOf("[tab]SOLO:") + TabParserFixtures.SOLO_BEND + listOf("[/tab]", "", "*Little note about the lid*")
            ).joinToString("\r\n")
        val r = parseSongReference(SyntheticSong.ugPage(content = content, tonality = null))
        assertEquals(listOf("INTRO/OUTRO", "CHORUS", "NEXT VERSE", "SOLO"), r.sections.map { it.name })
        assertEquals(listOf(SectionKind.INTRO, SectionKind.CHORUS, SectionKind.VERSE, SectionKind.SOLO), r.sections.map { it.kind })
        assertEquals(3, r.sections[0].repeats)
        assertEquals(listOf("Wake up slow and put the kettle on...", "Steam is rising...", "Pour another cup..."), r.phraseSlots.map { it.text })
        assertTrue(r.phraseSlots.all { it.chords == listOf("A", "G") })
        assertEquals(RhythmSource.SPACING, r.melodies.single().rhythm)
    }

    @Test fun oneQuotedLineDoesNotMakeACuePage() {
        val sheet = listOf("[Verse]", "Am                D", "Wake up slow and put the kettle on", "\"Steam is rising, morning almost gone\"",
            "Pour another cup for me", "Pour another cup, let it be").joinToString("\n")
        assertEquals(4, parseSongReference(sheet).phraseSlots.size)
    }

    @Test fun compoundLabelAnswersForEachKindItNames() {
        val r = parseSongReference("[Verse/Bridge]\nAm D\nWake up slow\n[Bridge]\n\n[Last verse]")
        assertEquals(listOf(SectionKind.VERSE, SectionKind.BRIDGE, SectionKind.VERSE), r.sections.map { it.kind })
        assertEquals(listOf(null, 0, 0), r.sections.map { it.sameAs })
    }

    @Test fun twiceRepeatsTheSectionAndNeverOpensOne() {
        val r = parseSongReference("Twice\n[Verse]\nAm D\nWake up slow\nTwice")
        assertEquals(listOf("Verse"), r.sections.map { it.name }); assertEquals(2, r.sections.single().repeats); assertEquals(1, r.phraseSlots.size)
    }

    @Test fun noChordBeforeChordsKeepsThem() {
        assertEquals(listOf("Am", "D"), parseSongReference("[Verse]\nN.C.  Am  D\nWake up slow").phraseSlots.single().chords)
    }

    @Test fun malformedPagesNeverThrow() {
        listOf("", "<div class=\"js-store\" data-content=\"{&quot;store\">", "[", "e|--\u0085--|\n[Chorus x]", "&#xFFFFFFF; &#0;")
            .forEach { parseSongReference(it) }
    }
}
