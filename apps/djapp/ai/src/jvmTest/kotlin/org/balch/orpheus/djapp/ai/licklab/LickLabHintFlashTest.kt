package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LickLabHintFlashTest {
    private val hint = KeyHint(changeBeat = 10.0, windowBeats = 1.0, tones = setOf(2, 4), preferred = 4)
    private val next = hint.copy(changeBeat = 12.0, preferred = 6)

    @Test
    fun `the flash fires once the clock passes the change and fades out`() {
        val f = HintFlash()
        assertNull(f.step(hint, now = 9.6, ms = 0L, count = 8))
        assertEquals(0f, f.step(hint, now = 10.01, ms = 100L, count = 8))
        assertEquals(4, f.key)
        assertEquals(0.5f, f.step(hint, now = 10.2, ms = 275L, count = 8)!!, 1e-3f)
        assertNull(f.step(hint, now = 10.5, ms = 500L, count = 8))
    }

    @Test
    fun `a hint retracted before its change never flashes`() {
        val f = HintFlash()
        f.step(hint, now = 9.6, ms = 0L, count = 8)
        assertNull(f.step(null, now = 9.8, ms = 16L, count = 8))
        assertNull(f.step(null, now = 10.05, ms = 32L, count = 8))
    }

    @Test
    fun `a stopped frame clock ends the flash`() {
        val f = HintFlash()
        f.step(hint, now = 9.6, ms = 0L, count = 8)
        assertNotNull(f.step(null, now = 10.01, ms = 16L, count = 8))
        assertNull(f.step(null, now = 10.05, ms = null, count = 8))
        assertNull(f.step(null, now = 10.06, ms = 40L, count = 8))
    }

    @Test
    fun `the next hint does not swallow a change the clock has passed`() {
        val f = HintFlash()
        f.step(hint, now = 9.6, ms = 0L, count = 8)
        assertEquals(0f, f.step(next, now = 10.05, ms = 16L, count = 8))
        assertEquals(4, f.key)
    }

    @Test
    fun `a change passed long ago never flashes late`() {
        val f = HintFlash()
        f.step(hint, now = 9.6, ms = 0L, count = 8)
        assertNull(f.step(hint, now = 11.0, ms = 16L, count = 8))
    }
}
