package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipLapTest {
    // A one-beat window ending on beat 10, at 500 ms a beat.
    private val hint = KeyHint(changeBeat = 10.0, windowBeats = 1.0, tones = emptySet(), preferred = 3)

    @Test
    fun `the lap runs on frame time from its window to the change`() {
        val lap = ZipLap()
        assertEquals(0f, lap.progress(hint, now = 9.0, ms = 1_000, msPerBeat = 500.0))
        // A nudged beat clock does not move the lap; only frame time does.
        assertEquals(0.5f, lap.progress(hint, now = 9.9, ms = 1_250, msPerBeat = 500.0))
        assertNull(lap.progress(hint, now = 10.0, ms = 1_500, msPerBeat = 500.0))
    }

    @Test
    fun `a window opened late keeps the share already gone`() {
        val lap = ZipLap()
        assertEquals(0.25f, lap.progress(hint, now = 9.25, ms = 0, msPerBeat = 500.0))
        assertEquals(1f, lap.progress(hint, now = 9.9, ms = 374, msPerBeat = 500.0)!!, 0.01f)
    }

    @Test
    fun `a nudged change estimate never sends a running lap backwards`() {
        val lap = ZipLap()
        assertEquals(0f, lap.progress(hint, now = 9.0, ms = 1_000, msPerBeat = 500.0))
        assertEquals(0.5f, lap.progress(hint.copy(changeBeat = 9.95), now = 9.5, ms = 1_250, msPerBeat = 500.0))
        assertEquals(0.6f, lap.progress(hint.copy(changeBeat = 9.9), now = 9.6, ms = 1_300, msPerBeat = 500.0))
    }

    @Test
    fun `no lap outside the window`() {
        val lap = ZipLap()
        assertNull(lap.progress(hint, now = 8.5, ms = 0, msPerBeat = 500.0))
        assertNull(lap.progress(hint, now = 10.2, ms = 0, msPerBeat = 500.0))
    }

    @Test
    fun `the nested runs reach the peak where all overlap`() {
        val a = zipLayerAlpha(0.8f, 6)
        var covered = 0f
        repeat(6) { covered += (1f - covered) * a }
        assertEquals(0.8f, covered, 0.001f)
    }
}
