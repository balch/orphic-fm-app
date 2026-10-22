package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.plugin.viz.VizHistory
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VizHistoryResamplerTest {
    private val ch = 3

    /**
     * An engine writing [blockRate] blocks a second from block [startBlock], polled at 60 Hz for
     * [seconds]. [sample] gives block k's value. Returns the resampler and each poll's tick end.
     */
    private fun run(
        blockRate: Double, seconds: Double, startBlock: Int = 10_000,
        resampler: VizHistoryResampler = VizHistoryResampler(ch..ch, 8),
        sample: (Int) -> Float = { 0.5f },
    ): Pair<VizHistoryResampler, List<Int>> {
        var read = startBlock
        val buf = FloatArray(480)
        val ends = mutableListOf<Int>()
        for (f in 0 until (seconds * 60).toInt()) {
            val t = f / 60.0
            val written = startBlock + floor(t * blockRate).toInt()
            val count = written - read
            for (i in 0 until count) buf[i] = sample(read + i)
            if (count > 0) resampler.write(ch, buf, count, written)
            read = written
            resampler.endPoll(t)
            ends += resampler.ends[ch]
        }
        return resampler to ends
    }

    private fun tickRate(ends: List<Int>, seconds: Double): Double =
        (ends.last() - ends[ends.size - 1 - (seconds * 60).toInt()]) / seconds

    @Test fun `ticks run at the fixed rate whatever the engine's block rate`() {
        for (blockRate in listOf(93.75, 500.0, 50.0, 187.5)) {
            val (_, ends) = run(blockRate, seconds = 8.0)
            assertEquals(VizHistory.RATE, tickRate(ends, 4.0), 1.5, "at $blockRate blocks a second")
        }
    }

    @Test fun `a desktop ring maps block for tick`() {
        val (r, _) = run(93.75, seconds = 4.0) { k -> (k % 7) / 7f }
        val ring = r.rings[ch]
        val end = r.ends[ch]
        // Neighbouring ticks keep the ramp's steps: no merged or held blocks in a recent stretch.
        var steps = 0
        for (t in end - 60 until end - 1) if (ring[(t + 1) and (ring.size - 1)] != ring[t and (ring.size - 1)]) steps++
        assertTrue(steps >= 55, "only $steps of 59 ticks moved")
    }

    @Test fun `a one-block hit survives the merge`() {
        val (r, _) = run(500.0, seconds = 3.0) { k -> if (k == 10_000 + 1000) 0.9f else 0.01f }
        assertTrue(r.rings[ch].any { it == 0.9f }, "the hit was merged away")
    }

    @Test fun `slow blocks hold their value across the ticks between them`() {
        val (r, _) = run(50.0, seconds = 3.0)
        val ring = r.rings[ch]
        val end = r.ends[ch]
        for (t in end - 150 until end) assertEquals(0.5f, ring[t and (ring.size - 1)], "tick $t dropped out")
    }

    @Test fun `a restarted engine keeps the ticks moving forward`() {
        val (r, before) = run(500.0, seconds = 2.0, startBlock = 90_000)
        val (_, after) = run(500.0, seconds = 2.0, startBlock = 0, resampler = r)
        assertTrue(after.first() >= before.last(), "ticks went back from ${before.last()} to ${after.first()}")
        assertEquals(VizHistory.RATE, tickRate(after, 1.0), 3.0)
    }
}
