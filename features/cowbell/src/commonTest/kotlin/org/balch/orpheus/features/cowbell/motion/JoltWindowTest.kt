package org.balch.orpheus.features.cowbell.motion

import kotlin.test.Test
import kotlin.test.assertEquals

class JoltWindowTest {
    @Test
    fun joltWindowUsesReceiptTime() {
        val w = JoltWindow()
        w.record(100, 0.5f)
        w.record(120, 2.0f)
        w.record(200, 1.0f)
        assertEquals(2.0f, w.peak(90, 130))
        assertEquals(0f, w.peak(150, 190))
        assertEquals(1.0f, w.peak(150, 250))
    }

    @Test
    fun joltWindowBoundsAreInclusive() {
        val w = JoltWindow()
        w.record(100, 3f)
        assertEquals(3f, w.peak(100, 100))
        assertEquals(0f, w.peak(101, 200))
        assertEquals(0f, w.peak(0, 99))
    }

    @Test
    fun emptyWindowPeaksAtZero() {
        assertEquals(0f, JoltWindow().peak(0, Long.MAX_VALUE))
    }

    @Test
    fun joltWindowWraps() {
        val w = JoltWindow(capacity = 64)
        for (i in 0 until 200) w.record(i.toLong(), (i + 1).toFloat())
        // The last 64 records are t = 136..199.
        assertEquals(200f, w.peak(136, 199))
        assertEquals(137f, w.peak(136, 136))
        assertEquals(0f, w.peak(0, 135))
        assertEquals(200f, w.peak(0, 199))
    }

    @Test
    fun clearForgetsEverything() {
        val w = JoltWindow()
        w.record(10, 5f)
        w.clear()
        assertEquals(0f, w.peak(0, 100))
    }

    @Test
    fun peakRemembersWhenItHappened() {
        val w = JoltWindow()
        w.record(10, 1f)
        w.record(20, 4f)
        w.record(30, 2f)
        assertEquals(4f, w.peak(0, 100))
        assertEquals(20L, w.lastPeakAtMillis)
        w.peak(50, 100)
        assertEquals(-1L, w.lastPeakAtMillis)
    }
}
