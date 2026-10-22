package org.balch.orpheus.features.visualizations.viz

import org.balch.orpheus.core.plugin.viz.VizHistory
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

class SmoothScrollTest {
    private val blockRate = 94.0
    private val frame = 1.0 / 60

    /**
     * A ring filling at [blockRate], polled once a frame, drawn half a frame after the poll.
     * Returns each frame's (position, newest) pair.
     */
    private fun run(clock: ScrollClock, frames: Int, startEnd: Int = 1000): List<Pair<Double, Int>> {
        val base = TimeSource.Monotonic.markNow()
        return List(frames) { f ->
            val t = f * frame
            val end = startEnd + floor(t * blockRate).toInt()
            val arrivedAt = base + (t * 1e9).toLong().nanoseconds
            val now = base + ((t + frame / 2) * 1e9).toLong().nanoseconds
            clock.position(end, arrivedAt, now) to clock.rel(end)
        }
    }

    @Test fun `the scroll moves the same distance every frame though polls bring one or two samples`() {
        val clock = ScrollClock(lagSeconds = 0.025)
        val steps = run(clock, frames = 600).map { it.first }.zipWithNext { a, b -> b - a }.drop(120)
        val perFrame = blockRate * frame
        val worst = steps.maxOf { abs(it - perFrame) }
        assertTrue(worst < 0.15, "worst step ${worst} samples off ${perFrame}")
        assertEquals(blockRate, clock.rate, 1.0)
    }

    @Test fun `the scroll never runs past the newest sample`() {
        val clock = ScrollClock(lagSeconds = 0.025)
        for ((position, newest) in run(clock, frames = 300)) assertTrue(position <= newest, "$position past $newest")
    }

    @Test fun `a restarted engine counting from zero starts the scroll over`() {
        val clock = ScrollClock(lagSeconds = 0.025)
        run(clock, frames = 60, startEnd = 50_000)
        val (position, newest) = run(clock, frames = 1, startEnd = 3).single()
        assertEquals(3, newest)
        assertTrue(position <= newest && position > newest - 10.0, "restarted at $position")
    }

    @Test fun `a bucket keeps its value as the ring scrolls past it`() {
        // A ramp written into the ring a block at a time, as the poll does: bucket 300 (blocks
        // 900..902) must read the same until the ring laps it.
        val stride = 3
        val ring = FloatArray(VizHistory.RING_SIZE)
        val seen = mutableSetOf<Float>()
        for (end in 1..5000) {
            ring[(end - 1) and (ring.size - 1)] = ((end - 1) % 97) / 97f
            if (end >= 903 && end - ring.size <= 300 * stride) seen += bucketPeak(ring, end, 300, stride, signed = false)
        }
        assertEquals(1, seen.size, "bucket 300 read $seen")
    }

    @Test fun `a clock's blocks keep their ring slots`() {
        val clock = ScrollClock(lagSeconds = 0.025)
        val (_, newest) = run(clock, frames = 1, startEnd = 70_001).single()
        assertEquals(70_000 and (VizHistory.RING_SIZE - 1), (newest - 1) and (VizHistory.RING_SIZE - 1))
    }

    @Test fun `a signed bucket keeps the sample furthest from zero`() {
        val ring = floatArrayOf(0.2f, -0.7f, 0.5f, 0f)
        assertEquals(-0.7f, bucketPeak(ring, 3, 0, 3, signed = true))
        assertEquals(0.5f, bucketPeak(ring, 3, 0, 3, signed = false))
    }
}
