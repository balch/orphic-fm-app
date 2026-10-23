package org.balch.orpheus.djapp.ai.songref

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongLinesTest {
    @Test fun syllables() {
        assertEquals(9, countSyllables("Wake up slow and put the kettle on")); assertEquals(9, countSyllables("Steam is rising, morning almost gone"))
        assertEquals(7, countSyllables("Pour another cup for me")); assertEquals(8, countSyllables("Pour another cup, let it be"))
    }

    @Test fun labels() {
        assertEquals("Verse 1" to 1, parseLabel("[Verse 1]")); assertEquals("Chorus" to 2, parseLabel("[Chorus x2]"))
        assertEquals("Intro" to 1, parseLabel("Intro:")); assertEquals("VERSE 1" to 1, parseLabel("VERSE 1"))
        assertNull(parseLabel("[Am]")); assertNull(parseLabel("Verse after verse we sang along all night"))
        assertEquals(SectionKind.CHORUS, sectionKindOf("Refrain")); assertEquals(SectionKind.PRE_CHORUS, sectionKindOf("Pre-Chorus"))
        assertEquals(SectionKind.INTERLUDE, sectionKindOf("Instrumental break")); assertEquals(SectionKind.OTHER, sectionKindOf("Song"))
    }

    @Test fun lineKinds() {
        assertEquals(LineKind.CHORDS, classifyLine("Am        D    (x2)")); assertEquals(LineKind.LYRIC, classifyLine("\"Wake up slow\" and go"))
        assertEquals(LineKind.TAB, classifyLine("e|---0---|")); assertEquals(LineKind.REPEAT, classifyLine("(x2)"))
        listOf("Tuning: E A D G B E", "Capo 2", "Tabbed by someone", "http://example.com", "=====").forEach { assertEquals(LineKind.META, classifyLine(it), it) }
    }

    @Test fun overlongTextIsMetaNotALyric() {
        val prose = "the kettle hums along while morning light comes in ".repeat(4).trim()
        assertTrue(prose.length > 160); assertEquals(LineKind.META, classifyLine(prose))
        assertEquals(LineKind.LYRIC, classifyLine(prose.take(150)))
    }

    @Test fun inlineChordsDoNotCountTowardTheLyricLength() {
        val sung = "[Am]Wake up slow and [D]put the kettle on, [Am]steam is rising [D]and the morning almost [C]gone, " +
            "[G]pour another cup for [Am]me and [D]let the whistle sing along"
        assertTrue(sung.length > 160); assertEquals(LineKind.LYRIC, classifyLine(sung))
        val slot = parseSongReference("[Verse 1]\n$sung").phraseSlots.single()
        assertTrue(slot.text.startsWith("Wake up slow"), slot.text); assertTrue(slot.chords.isNotEmpty(), slot.chords.toString())
        val prose = "the kettle hums along while morning light comes in and the steam curls up the window pane as the teapots " +
            "sing a slow and sleepy song about the lid that rattles on every day"
        assertTrue(prose.length >= 170); assertEquals(LineKind.META, classifyLine(prose))
    }

    @Test fun longLinesClassifyFast() {
        val mixed = buildString { var k = 0; while (length < 20_000) { append(if (k % 3 == 0) "  " else "ab "); k++ } }
        listOf("repeat" + " ".repeat(5000) + "z", mixed, "<" + " ".repeat(20_000), "Intro:" + " ".repeat(20_000) + "z").forEach { line ->
            classifyLine(line)
            val started = System.nanoTime()
            classifyLine(line)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 100, line.take(12))
        }
        val started = System.nanoTime()
        extractPageText("<html>" + "<".repeat(20_000) + "<script" + " ".repeat(20_000) + "<" + " ".repeat(20_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
    }

    @Test fun labelsCarryingChords() {
        listOf("Intro: Am  G  F  E", "Verse 2: Am D", "[Chorus] C G", "Guitar solo:", "Solo:").forEach { assertEquals(LineKind.LABEL, classifyLine(it), it) }
        assertEquals("Intro" to 1, parseLabel("Intro: Am  G  F  E")); assertEquals("Chorus" to 1, parseLabel("[Chorus] C G"))
        assertEquals(SectionKind.SOLO, sectionKindOf(parseLabel("Guitar solo:")!!.first))
        assertNull(parseLabel("[Am] Wake up slow"))
    }

    @Test fun instructionLines() {
        assertEquals(LineKind.CHORDS, classifyLine("Am  D  (let ring)"))
        assertEquals(listOf(4, 3, 4, 2, 3), listOf("Repeat 4 times", "play 3 times", "x4", "(2 times)", "(\u00D73)").map { repeatOf(it)?.count })
        listOf("Repeat 4 times", "play 3 times", "x4", "X 4").forEach { assertEquals(LineKind.REPEAT, classifyLine(it), it) }
        listOf("let ring", "Palm mute throughout", "PM", "Fade out", "Thanks to Ann", "Thank you for reading", "Submitted by Bob",
            "Corrections welcome", "Strum: D DU UDU", "Strumming pattern below", "Guitar solo over the verse", "End", "N.C.", "[N.C.]")
            .forEach { assertEquals(LineKind.META, classifyLine(it), it) }
        assertEquals(LineKind.LYRIC, classifyLine("[F#m7]Oh [C#7]no"))
    }

    @Test fun unicodeSpacesAndEntities() {
        assertEquals("Am  D", extractPageText("Am\u00A0\u2003D").text)
        assertEquals("don\u2019t \u2018a\u2019 \u201Cb\u201D \u2026 \u2013 \u2014 ' x y", htmlUnescape("don&rsquo;t &lsquo;a&rsquo; &ldquo;b&rdquo; &hellip; &ndash; &mdash; &apos; x&nbsp;y"))
    }

    @Test fun compoundAndOrdinalLabels() {
        listOf(
            "INTRO/BRIDGE:" to SectionKind.INTRO, "Verse & Chorus" to SectionKind.VERSE, "Intro and verse" to SectionKind.INTRO,
            "THIRD VERSE:" to SectionKind.VERSE, "First chorus" to SectionKind.CHORUS, "Final chorus" to SectionKind.CHORUS, "Last verse" to SectionKind.VERSE,
        ).forEach { (line, kind) -> assertEquals(kind, sectionKindOf(parseLabel(line)?.first ?: error(line)), line) }
        assertEquals("INTRO/BRIDGE" to 1, parseLabel("INTRO/BRIDGE:")); assertEquals("THIRD VERSE" to 1, parseLabel("THIRD VERSE:"))
    }

    @Test fun labelsWithRepeatsOrNotes() {
        assertEquals("Chorus" to 2, parseLabel("Chorus: x2")); assertEquals("Verse 1" to 2, parseLabel("Verse 1: (x2)"))
        assertEquals("Intro" to 1, parseLabel("Intro: (let ring)")); assertEquals("Verse" to 2, parseLabel("Verse: x2"))
        listOf("Chorus: x2", "Verse 1: (x2)", "Intro: (let ring)", "Verse: x2").forEach { assertEquals(LineKind.LABEL, classifyLine(it), it) }
    }

    @Test fun figureCaptions() {
        assertEquals(Caption(5), captionOf("Figure 2c: (Play 5 times)")); assertEquals(Caption(null), captionOf("Figure 2d: (Play 6th time)"))
        assertEquals(Caption(4), captionOf("Riff 2: (x4)")); assertEquals(Caption(null), captionOf("Fig. 3"))
        assertNull(captionOf("Figure it out on your own")); assertEquals(LineKind.LABEL, classifyLine("Riff 2:"))
        listOf("Figure 2c: (Play 5 times)", "Figure 2d: (Play 6th time)", "Fig. 3", "*Little note about the lid*")
            .forEach { assertEquals(LineKind.META, classifyLine(it), it) }
    }

    @Test fun instructionsVersusLyricsThatStartTheSameWay() {
        listOf("End", "End of song", "Fade", "Fade out", "(fade out)", "fade to end", "until the music fades away", "Strum: D DU", "Strumming pattern below", "Strum")
            .forEach { assertEquals(LineKind.META, classifyLine(it), it) }
        listOf("Fade into the morning light", "End of the road we walked", "Strum the kettle softly", "Solo in the morning light")
            .forEach { assertEquals(LineKind.LYRIC, classifyLine(it), it) }
    }

    @Test fun noChordTokensInsideAChordLine() {
        assertEquals(LineKind.CHORDS, classifyLine("N.C.  Am  D")); assertEquals(listOf("Am", "D"), chordLineChords("N.C.  Am  D")!!.map { it.name })
        assertEquals(LineKind.META, classifyLine("N.C.")); assertEquals(RepeatMark(2, null), repeatOf("Twice"))
    }

    @Test fun lineSeparatorsReadAsSpaces() {
        assertEquals("Am D x y", extractPageText("Am\u2028D\u0085x\u2029y").text)
    }

    @Test fun pageTextFromUgAndHtmlAndPlain() {
        val ug = extractPageText(SyntheticSong.ugPage())
        assertEquals("ultimate-guitar", ug.format); assertEquals("Kettle Morning", ug.title); assertEquals("Am", ug.tonality)
        assertFalse(ug.text.contains("[ch]")); assertFalse(ug.text.contains("\r"))
        assertEquals("html", extractPageText("<html><body><pre>Am  D\nWake up slow</pre></body></html>").format)
        assertEquals("plain", extractPageText(SyntheticSong.PLAIN).format)
        assertEquals("a & b ' \u00E9", htmlUnescape("a &amp; b &#039; &#xe9;"))
    }
}
