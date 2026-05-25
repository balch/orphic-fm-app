package org.balch.orpheus.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FadeCurveTest {
    @Test
    fun `LOG curve endpoints are 0 and 1`() {
        assertTrue(abs(FadeCurve.LOG.curve(0f) - 0f) < 1e-3f, "LOG(0) = ${FadeCurve.LOG.curve(0f)}")
        assertTrue(abs(FadeCurve.LOG.curve(1f) - 1f) < 1e-3f, "LOG(1) = ${FadeCurve.LOG.curve(1f)}")
    }

    @Test
    fun `LOG curve midpoint is approximately -30dB`() {
        // For a fade-OUT the curve maps wall-clock t to fade progress.
        // At t=0.5 we expect the output amplitude (1 - shaped) to sit roughly
        // halfway between full and the -60dB floor → about -30dB → ~0.0316 amplitude.
        val shaped = FadeCurve.LOG.curve(0.5f)
        val outAmplitude = 1f - shaped
        val db = 20f * log10(outAmplitude.coerceAtLeast(1e-6f))
        assertTrue(db in -36f..-24f, "midpoint dB=$db (expected ~-30 ± 6)")
    }

    @Test
    fun `LOG curve is monotonically increasing`() {
        var prev = -1f
        for (i in 0..100) {
            val v = FadeCurve.LOG.curve(i / 100f)
            assertTrue(v >= prev - 1e-5f, "non-monotonic at t=${i/100f}: prev=$prev v=$v")
            prev = v
        }
    }

    @Test
    fun `LINEAR curve preserved`() {
        assertEquals(0f, FadeCurve.LINEAR.curve(0f))
        assertEquals(0.5f, FadeCurve.LINEAR.curve(0.5f))
        assertEquals(1f, FadeCurve.LINEAR.curve(1f))
    }
}
