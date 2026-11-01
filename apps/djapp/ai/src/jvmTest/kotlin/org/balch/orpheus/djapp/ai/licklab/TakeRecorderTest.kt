package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TakeRecorderTest {
    private fun TakeRecorder.play(start: Double, end: Double, midi: Int = 60) {
        noteOn(start, midi, 0.8f, false, -1f)
        noteOff(end)
    }

    @Test
    fun `a bar of silence closes the take`() {
        val r = TakeRecorder(4)
        r.play(0.0, 0.4)
        r.play(0.5, 0.9)
        r.play(1.5, 1.9)
        assertNull(r.tick(4.0))
        assertNull(r.tick(5.8))
        val take = assertNotNull(r.tick(6.0))
        assertEquals(3, take.notes.size)
        assertEquals(0, take.startBar)
        assertNull(r.tick(20.0))
    }

    @Test
    fun `start bar follows the first note`() {
        val r = TakeRecorder(4)
        r.play(4.1, 4.5)
        assertEquals(1, assertNotNull(r.flush(5.0)).startBar)
    }

    @Test
    fun `a held note keeps the take open and a new note ends the sounding one`() {
        val r = TakeRecorder(4)
        r.noteOn(0.0, 60, 0.8f, false, -1f)
        assertNull(r.tick(30.0))
        r.noteOn(1.0, 64, 0.8f, true, 0.3f)
        val take = assertNotNull(r.flush(2.0))
        assertEquals(listOf(1.0, 2.0), take.notes.map { it.endBeat })
        assertEquals(true, take.notes[1].slidIn)
    }

    @Test
    fun `a note after a long gap hands back the closed take`() {
        val r = TakeRecorder(4)
        r.play(0.0, 1.0)
        val closed = assertNotNull(r.noteOn(9.0, 62, 0.8f, false, -1f))
        assertEquals(1, closed.notes.size)
        r.noteOff(9.5)
        assertEquals(2, assertNotNull(r.flush(10.0)).startBar)
    }

    @Test
    fun `a note that lasted no time is dropped`() {
        val r = TakeRecorder(4)
        r.play(0.0, 1.0, midi = 60)
        r.noteOn(1.0, 62, 0.8f, false, -1f)
        r.noteOn(1.0, 64, 0.8f, false, -1f)
        r.play(2.0, 2.0, midi = 65)
        val take = assertNotNull(r.flush(3.0))
        assertEquals(listOf(60, 64), take.notes.map { it.midi })
    }
}
