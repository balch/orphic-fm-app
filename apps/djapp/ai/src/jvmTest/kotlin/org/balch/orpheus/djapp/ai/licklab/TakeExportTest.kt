package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.Rest
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

class TakeExportTest {
    private val vibe = Vibe(
        name = "t",
        tracks = List(8) {
            TrackVoice(OrpheusEngine(engineId = OrpheusEngineId.VA), OrpheusEngine(engineId = OrpheusEngineId.VA))
        },
        lickOctave = 5,
        bassLineOctave = 2,
        bpm = 120f,
        rootNote = RootNote.B,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(0f, 0f, 36, 72, RhythmPattern.SPARSE.density),
    )
    private val lead = SlotRef(LickSlot.Lead)
    private val v = 0.9f

    private fun midi(degree: Int) = LickPitch.degreeToMidi(degree, vibe, lead)

    private fun note(start: Double, end: Double, midi: Int, slid: Boolean = false, glide: Float = -1f) =
        TakeNote(start, end, midi, v, slid, glide)

    private fun take(vararg n: TakeNote, startBar: Int = 0) = Take(1, n.toList(), startBar, 4)

    @Test
    fun `steps with a gap become a rest`() {
        val out = TakeExport.toSteps(
            take(note(0.0, 0.5, midi(0)), note(0.5, 0.75, midi(4)), note(1.0, 2.0, midi(-1))),
            vibe, lead,
        )
        assertEquals(
            listOf(LickStep(0, 0.5f, v), LickStep(4, 0.25f, v), Rest(0.25f), LickStep(-1, 1f, v)),
            out.steps,
        )
        assertEquals(4, out.loopLength)
        assertTrue(out.chromaticNotes.isEmpty())
    }

    @Test
    fun `leading gap from the bar line becomes a rest`() {
        val out = TakeExport.toSteps(take(note(4.5, 5.0, midi(0)), startBar = 1), vibe, lead)
        assertEquals(listOf(Rest(0.5f), LickStep(0, 0.5f, v)), out.steps)
        assertEquals(4, out.loopLength)
    }

    @Test
    fun `quantises to sixteenths and rounds the loop up to whole bars`() {
        val out = TakeExport.toSteps(take(note(0.13, 0.4, midi(0)), note(4.0, 4.9, midi(2))), vibe, lead)
        assertEquals(listOf(Rest(0.25f), LickStep(0, 0.25f, v)), out.steps.take(2))
        assertEquals(8, out.loopLength)
    }

    @Test
    fun `a slid in note carries its glide rate`() {
        val out = TakeExport.toSteps(take(note(0.0, 0.5, midi(0)), note(0.5, 1.0, midi(2), true, 0.3f)), vibe, lead)
        assertEquals(-1f, out.steps[0].glideRate)
        assertEquals(0.3f, out.steps[1].glideRate)
    }

    @Test
    fun `a chromatic note snaps and is listed`() {
        val out = TakeExport.toSteps(take(note(0.0, 0.5, midi(0)), note(0.5, 1.0, 72)), vibe, lead)
        assertEquals(0, out.steps[1].scaleDegree)
        assertEquals(listOf(1), out.chromaticNotes)
        assertTrue("// played C natural" in TakeExport.toKotlin(out, lead))
    }

    @Test
    fun `kotlin output for each slot`() {
        val out = TakeExport.toSteps(take(note(0.0, 0.5, midi(0)), note(1.0, 1.5, midi(2), true, 0.3f)), vibe, lead)
        val bass = TakeExport.toKotlin(out, SlotRef(LickSlot.Bass))
        assertEquals(
            """
            bassLine = Lick(steps = listOf(
                LickStep(0, 0.5f, 0.9f),
                Rest(0.5f),
                LickStep(2, 0.5f, 0.9f, glideRate = 0.3f),
            ), loopLength = 4)
            """.trimIndent(),
            bass,
        )
        assertTrue(TakeExport.toKotlin(out, lead).startsWith("lick = Lick(steps = listOf("))
        val pool = TakeExport.toKotlin(out, SlotRef(LickSlot.Pool, 2))
        assertTrue(pool.lineSequence().first().contains("pool entry 2"))
        assertTrue(TakeExport.toKotlin(out, SlotRef(LickSlot.Solo, 5)).lineSequence().first().contains("track 5"))
    }

    @Test
    fun `an early downbeat lands on its own bar with no leading rest`() {
        val out = TakeExport.toSteps(take(note(3.95, 4.45, midi(0))), vibe, lead)
        assertEquals(listOf(LickStep(0, 0.5f, v)), out.steps)
        assertEquals(4, out.loopLength)
    }

    @Test
    fun `over 64 steps is flagged and nothing is dropped`() {
        val notes = Array(65) { note(it * 0.25, it * 0.25 + 0.25, midi(0)) }
        val out = TakeExport.toSteps(take(*notes), vibe, lead)
        assertEquals(65, out.steps.size)
        assertTrue(out.overCap)
        assertTrue(TakeExport.toKotlin(out, lead).startsWith("// over 64 steps: split this take before pasting\n"))
        assertTrue(!TakeExport.toSteps(take(note(0.0, 0.5, midi(0))), vibe, lead).overCap)
    }

    @Test
    fun `an overlap is clipped to the next start`() {
        val out = TakeExport.toSteps(take(note(0.0, 1.0, midi(0)), note(0.5, 1.0, midi(2))), vibe, lead)
        assertEquals(listOf(LickStep(0, 0.5f, v), LickStep(2, 0.5f, v)), out.steps)
        assertEquals(0, out.mergedCount)
    }

    @Test
    fun `a note played under a held key is marked hammered, a slide is not`() {
        val out = TakeExport.toSteps(
            take(
                note(0.0, 0.5, midi(0)),
                note(0.5, 1.0, midi(2)),             // key still held: hammered
                note(1.0, 1.5, midi(4), true, 0.3f), // slid in: glides, not marked
                note(2.0, 2.5, midi(0)),             // after a gap: a fresh strike
            ),
            vibe, lead,
        )
        assertEquals(setOf(1), out.hammered)
        val lines = TakeExport.toKotlin(out, lead).lines()
        assertTrue(lines[2].endsWith("// hammered"), lines[2])
        assertEquals(1, lines.count { "// hammered" in it })
    }

    @Test
    fun `two notes on the same sixteenth merge and are counted`() {
        val out = TakeExport.toSteps(take(note(0.0, 0.1, midi(0)), note(0.05, 0.5, midi(2))), vibe, lead)
        assertEquals(listOf(LickStep(2, 0.5f, v)), out.steps)
        assertEquals(1, out.mergedCount)
    }

    @Test
    fun `a degree outside the allowed range is clamped and listed`() {
        val out = TakeExport.toSteps(take(note(0.0, 0.5, 5)), vibe, lead)
        val d = (LickPitch.midiToDegree(5, vibe, lead) as DegreeOrChromatic).let {
            when (it) { is DegreeOrChromatic.Degree -> it.degree; is DegreeOrChromatic.Chromatic -> it.nearest }
        }
        assertEquals(-32, out.steps[0].scaleDegree)
        assertEquals(mapOf(0 to d), out.clamped)
        assertTrue("// clamped from degree $d" in TakeExport.toKotlin(out, lead))
    }

    @Test
    fun `a take that follows chords says so in its Kotlin and its marks`() {
        val out = TakeExport.toSteps(take(note(0.0, 0.5, midi(0))), vibe, lead)
        val kotlin = TakeExport.toKotlin(out, lead, followsChords = true)
        assertEquals("// ${TakeExport.FOLLOWS_CHORDS_NOTE}", kotlin.lineSequence().first())
        assertTrue("follows chords" !in TakeExport.toKotlin(out, lead))
        assertEquals(listOf(FOLLOWS_CHORDS_MARK), takeMarks(out, followsChords = true))
        assertEquals(emptyList(), takeMarks(out))
    }

    @Test
    fun `an exported rest keeps the default velocity a pasted rest has`() {
        val out = TakeExport.toSteps(take(note(1.0, 1.5, midi(0))), vibe, lead)
        assertEquals(Rest(1f), out.steps.first())
        assertEquals(LickStep(0, 1f).velocity, out.steps.first().velocity)
        assertTrue(TakeExport.toKotlin(out, lead).contains("    Rest(1f),"))
    }
}
