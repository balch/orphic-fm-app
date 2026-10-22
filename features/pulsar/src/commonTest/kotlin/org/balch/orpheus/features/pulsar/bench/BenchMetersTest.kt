package org.balch.orpheus.features.pulsar.bench

import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.balch.orpheus.core.audio.dsp.BenchSummaryLayout as L

private const val SR = 48_000f
private const val FRAMES = 512

/** Mean per-frame K-weighted power (zL^2 + zR^2) that reads -20 LUFS. */
private val minus20Power = 10.0.pow((-20.0 + 0.691) / 10.0)

/** [blocks] summaries of [FRAMES] frames each, filled per summary by [fill]. */
private fun summaries(blocks: Int, fill: (FloatArray, Int) -> Unit): FloatArray {
    val out = FloatArray(blocks * L.FLOATS)
    for (b in 0 until blocks) {
        val off = b * L.FLOATS
        out[off + L.FRAMES] = FRAMES.toFloat()
        out[off + L.SAMPLES] = (FRAMES * 2).toFloat()
        fill(out, off)
    }
    return out
}

private fun FloatArray.setChannel(off: Int, c: Int, field: Int, v: Double) {
    this[off + L.channel(c) + field] = v.toFloat()
}

class BenchMetersTest {
    @Test
    fun sineReadsMinus20() {
        val m = BenchMeters(SR)
        val blocks = (10 * SR / FRAMES).toInt()
        val s = summaries(blocks) { a, off ->
            a.setChannel(off, L.OUT, L.K_ENERGY, minus20Power * FRAMES)
            a.setChannel(off, L.DRY, L.K_ENERGY, minus20Power * FRAMES)
        }
        m.add(s, blocks)
        val snap = m.snapshot()
        assertEquals(-20.0, snap.lufsIntegrated, 0.2)
        assertEquals(-20.0, snap.lufsShort, 0.2)
    }

    @Test
    fun relLuOfHalfTrack() {
        val m = BenchMeters(SR)
        val blocks = (4 * SR / FRAMES).toInt()
        val s = summaries(blocks) { a, off ->
            a.setChannel(off, L.DRY, L.K_ENERGY, minus20Power * FRAMES)
            a.setChannel(off, 0, L.K_ENERGY, 0.5 * minus20Power * FRAMES)
            a.setChannel(off, 1, L.K_ENERGY, 0.5 * minus20Power * FRAMES)
        }
        m.add(s, blocks)
        val ch = m.snapshot().channels
        assertEquals(10 * log10(0.5), ch[0].relLu, 0.01)
        assertEquals(10 * log10(0.5), ch[1].relLu, 0.01)
        assertEquals(L.CHANNELS, ch.size)
    }

    @Test
    fun resetClearsIntegrated() {
        val m = BenchMeters(SR)
        val blocks = (4 * SR / FRAMES).toInt()
        m.add(summaries(blocks) { a, off -> a.setChannel(off, L.OUT, L.K_ENERGY, minus20Power * FRAMES) }, blocks)
        assertTrue(m.snapshot().lufsIntegrated > -21.0)
        m.reset()
        assertEquals(-120.0, m.snapshot().lufsIntegrated)
    }
}
