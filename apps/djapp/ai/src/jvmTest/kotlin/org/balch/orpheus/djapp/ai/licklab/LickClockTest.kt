package org.balch.orpheus.djapp.ai.licklab

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LickClockTest {
    private var now = 0L
    private val clock = LickClock { now }

    @Test
    fun `between pulses the clock runs on`() {
        val pulse = Any()
        val a = clock.beat(500.0, pulse, 0.0)
        now = 50
        val b = clock.beat(500.0, pulse, 0.0)
        assertEquals(0.1, b - a, 1e-9)
        now = 120
        assertEquals(0.24, clock.beat(500.0, pulse, 0.0) - a, 1e-9)
    }

    @Test
    fun `a new pulse nudges toward its phase without a jump or a step back`() {
        clock.beat(500.0, Any(), 0.0)
        now = 1000
        // Two beats in, but the music says we are a tenth of a beat behind.
        val ahead = clock.beat(500.0, Any(), 0.9)
        assertTrue(abs(ahead - 1.975) < 1e-9, "$ahead")
        now = 1001
        assertTrue(clock.beat(500.0, Any(), 0.9) >= ahead)
    }

    @Test
    fun `a paused playhead's frozen phase does not hold the clock back`() {
        clock.beat(500.0, Any(), 1.0)
        // Paused: fresh pulses every 33 ms, all at the held step's end.
        var last = 0.0
        for (i in 1..30) {
            now = i * 33L
            last = clock.beat(500.0, Any(), 1.0)
        }
        assertEquals(30 * 33 / 500.0, last, 1e-9)
    }

    @Test
    fun `without a beat phase it counts time alone`() {
        clock.beat(400.0, null, null)
        now = 1000
        assertEquals(2.5, clock.beat(400.0, null, null), 1e-9)
    }
}
