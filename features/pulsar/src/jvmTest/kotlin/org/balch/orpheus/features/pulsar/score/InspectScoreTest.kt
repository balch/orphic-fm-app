package org.balch.orpheus.features.pulsar.score

import javax.sound.midi.MidiEvent
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectScoreTest {

    /** Track 0: three monophonic quarter notes. Track 1: a dyad, so polyphony > 1. */
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

    @Test
    fun `inspect reports note count and pitch range per track`() {
        val parts = MidiScoreImporter.inspect(sequence())
        assertEquals(3, parts[0].noteCount)
        assertEquals(60, parts[0].lowPitch)
        assertEquals(64, parts[0].highPitch)
    }

    @Test
    fun `a monophonic part reports polyphony 1 and a dyad reports 2`() {
        // This is the number that decides whether a part can ship in phase A at all.
        val parts = MidiScoreImporter.inspect(sequence())
        assertTrue(abs(parts[0].meanPolyphony - 1.0) < 0.01, "got ${parts[0].meanPolyphony}")
        assertTrue(abs(parts[1].meanPolyphony - 2.0) < 0.01, "got ${parts[1].meanPolyphony}")
    }

    @Test
    fun `peak polyphony reports the structural maximum, not the average`() {
        // Track 1 is a dyad throughout, so mean and peak agree at 2. The number that
        // matters for pool sizing is peak, and on a real part the two diverge.
        val parts = MidiScoreImporter.inspect(sequence())
        assertEquals(1, parts[0].peakPolyphony)
        assertEquals(2, parts[1].peakPolyphony)
    }

    @Test
    fun `a voice freed exactly as the next note starts is reused, not double-counted`() {
        // Back-to-back legato notes are one voice. Counting the hand-off would inflate the
        // pool by one per note-pair.
        val seq = Sequence(Sequence.PPQ, 480)
        val track = seq.createTrack()
        listOf(60, 62, 64).forEachIndexed { i, p ->
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, p, 100), i * 480L))
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, p, 0), (i + 1) * 480L))
        }
        assertEquals(1, MidiScoreImporter.inspect(seq)[0].peakPolyphony)
    }

    @Test
    fun `the ensemble peak sums across tracks and grows with a release tail`() {
        // Depends on whether the parts' peaks coincide. Here they do: both sound at tick 0.
        val seq = sequence()
        assertEquals(3, MidiScoreImporter.peakEnsemblePolyphony(seq))

        // Holding each voice a quarter note past its note-off keeps track 0's first note
        // alive under its second, so the ensemble needs one more voice than the floor.
        val withTail = MidiScoreImporter.peakEnsemblePolyphony(seq, releaseQuarters = 1.0)
        assertTrue(withTail > 3, "a release tail must not lower the count; got $withTail")
    }

    @Test
    fun `a flat-velocity source reports a zero spread`() {
        // A near-zero spread means the file carries no written dynamics, so scaling and
        // replacing are the same thing and the shape has to be authored by hand.
        val parts = MidiScoreImporter.inspect(sequence())
        assertTrue(parts[0].velocityStdDev < 0.001, "got ${parts[0].velocityStdDev}")
    }

    @Test
    fun `a velocity-0 NOTE_ON closes a note exactly like an explicit NOTE_OFF`() {
        // Running-status exports encode a note-off as NOTE_ON with velocity 0, not a real
        // NOTE_OFF message. Treating it as a second onset instead would make every note
        // infinite and every part look massively polyphonic — the single easiest way to
        // get this wrong.
        val seq = Sequence(Sequence.PPQ, 480)
        val track = seq.createTrack()
        track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0L))
        track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, 60, 0), 240L))

        val notes = MidiScoreImporter.readNotes(seq, 0)

        assertEquals(1, notes.size, "velocity-0 NOTE_ON should close the note, not open a second one")
        assertEquals(0L, notes[0].startTick)
        assertEquals(240L, notes[0].endTick)
        assertEquals(100, notes[0].velocity, "should keep the NOTE_ON's velocity, not the closing 0")
    }
}
