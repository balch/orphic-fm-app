package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import org.balch.orpheus.djapp.ai.licklab.HeldNotes.Change

class HeldNotesTest {
    @Test
    fun `overlap is legato and release returns to the held note`() {
        val h = HeldNotes()
        assertEquals(Change.Strike(60), h.press("a", 60))
        assertEquals(Change.Legato(64), h.press("b", 64))
        assertEquals(Change.Legato(60), h.release("b"))
        assertEquals(Change.Release, h.release("a"))
        assertEquals(Change.Strike(62), h.press("c", 62))
    }

    @Test
    fun `releasing a buried key changes nothing and repeats are ignored`() {
        val h = HeldNotes()
        h.press("a", 60)
        h.press("b", 64)
        assertEquals(Change.None, h.release("a"))
        assertEquals(Change.None, h.press("b", 64))
        assertEquals(Change.Release, h.release("b"))
        assertEquals(Change.None, h.release("zzz"))
    }
}
