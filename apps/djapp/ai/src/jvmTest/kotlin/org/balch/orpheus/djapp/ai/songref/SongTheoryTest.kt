package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongTheoryTest {
    @Test fun noteNamesAreScientific() {
        assertEquals("E2", noteName(40)); assertEquals("C#4", noteName(61)); assertEquals(10, pitchClassOf("Bb"))
    }

    @Test fun chordNamesParse() {
        assertEquals(ParsedChord("Am", 9, "m"), parseChordName("Am"))
        assertEquals("m", parseChordName("Amin")?.quality)
        assertEquals("maj7", parseChordName("Bbmaj7")?.quality)
        assertEquals("m7", parseChordName("F#m7")?.quality)
        assertEquals(4, parseChordName("C/E")?.bass)
        assertEquals("sus4", parseChordName("Dsus4")?.quality); assertEquals("5", parseChordName("G5")?.quality)
        assertEquals("", parseChordName("A")?.quality)
        assertNull(parseChordName("N.C.")); assertNull(parseChordName("Hello")); assertNull(parseChordName("Am7xyz"))
    }

    @Test fun fretShapesGetChordNames() {
        assertEquals("Am", nameChordShape(listOf(45, 52, 57, 60, 64))?.name)
        assertEquals("D", nameChordShape(listOf(50, 57, 62, 66))?.name)
        assertEquals("E", nameChordShape(listOf(40, 47, 52, 56, 59, 64))?.name)
        assertEquals("A5", nameChordShape(listOf(45, 52))?.name)
        assertEquals("Am/E", nameChordShape(listOf(52, 57, 60))?.name)
    }

    @Test fun keysFromChords() {
        fun key(vararg c: String, hint: String? = null) = guessKey(c.map { parseChordName(it)!! }, emptyList(), hint)!!
        assertEquals(RootNote.G to ScaleType.MAJOR, key("G", "C", "D", "G").let { it.rootNote to it.scaleType })
        assertEquals(RootNote.A to ScaleType.DORIAN, key("Am", "D", "Am", "D").let { it.rootNote to it.scaleType })
        assertEquals(RootNote.A to ScaleType.MIXOLYDIAN, key("A", "G", "D", "A").let { it.rootNote to it.scaleType })
        assertEquals(RootNote.E to ScaleType.MINOR, key("Em", "D", "C", "Em").let { it.rootNote to it.scaleType })
        val hinted = key("Am", "D", "C", "G", "Am", "D", hint = "Am")
        assertEquals(ScaleType.DORIAN, hinted.scaleType); assertEquals("page+chords", hinted.from); assertEquals("A dorian", hinted.name)
    }

    @Test fun tonicComesBeforeTheMode() {
        // Borrowed bVI and bVII chords must not pull the root away from the chord the song keeps returning to.
        listOf("B A G B A G F# B", "B G A G B G A F# B").forEach { p ->
            val k = guessKey(p.split(" ").map { parseChordName(it)!! }, emptyList(), null)!!
            assertEquals(RootNote.B, k.rootNote, "$p -> ${k.name}")
        }
    }

    @Test fun keyFromNotesAloneAndNothing() {
        val k = guessKey(emptyList(), listOf(57, 60, 62, 64, 67, 69, 64, 57), null)!!
        assertEquals(RootNote.A to ScaleType.MINOR, k.rootNote to k.scaleType); assertEquals("notes", k.from)
        assertNull(guessKey(emptyList(), emptyList(), null))
    }

    @Test fun fixedKeyIsUsedAsGiven() {
        val k = guessKey(listOf(parseChordName("Am")!!), emptyList(), null, RootNote.C, ScaleType.MAJOR)!!
        assertEquals(RootNote.C to ScaleType.MAJOR, k.rootNote to k.scaleType); assertEquals("request", k.from)
    }

    @Test fun fixedRootAloneIsARequest() {
        val k = guessKey(listOf("Am", "D", "Am", "D").map { parseChordName(it)!! }, emptyList(), "Am", fixedRoot = RootNote.A)!!
        assertEquals(RootNote.A to ScaleType.DORIAN, k.rootNote to k.scaleType); assertEquals("request", k.from)
        assertTrue(k.confidence < 1f, k.toString())
    }

    @Test fun fixedScaleAloneComparesAgainstTheNextRoot() {
        val k = guessKey(listOf("Am", "D", "Am", "D").map { parseChordName(it)!! }, emptyList(), null, fixedScale = ScaleType.DORIAN)!!
        assertEquals(RootNote.A to ScaleType.DORIAN, k.rootNote to k.scaleType); assertEquals("request", k.from)
        assertTrue(k.confidence in 0f..0.99f, k.toString()); assertTrue(k.alternatives.isNotEmpty(), k.toString())
    }

    @Test fun degreesMirrorTheEngine() {
        assertEquals(Degree(6, false), midiToDegree(55, 9, ScaleType.MINOR, 3))
        assertEquals(Degree(7, false), midiToDegree(57, 9, ScaleType.MINOR, 3))
        assertEquals(Degree(6, true), midiToDegree(56, 9, ScaleType.MINOR, 3))
        assertEquals(3, lickOctaveFor(45, 9))
        val aDorian = SongKey(RootNote.A, ScaleType.DORIAN, "A dorian", 1f, "chords")
        assertEquals(3, chordDegree(2, aDorian)); assertNull(chordDegree(5, aDorian))
    }
}
