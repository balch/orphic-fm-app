package org.balch.orpheus.features.pulsar.bench.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealFftTest {

    @Test
    fun sineBin() {
        val n = 4096
        val x = DoubleArray(n) { sin(2 * PI * 1000.0 * it / 48000.0) }
        val re = DoubleArray(n / 2 + 1)
        val im = DoubleArray(n / 2 + 1)
        RealFft(n).forward(x, re, im)
        val peak = (0..n / 2).maxBy { re[it] * re[it] + im[it] * im[it] }
        assertEquals((1000.0 * n / 48000.0).roundToInt(), peak)
    }

    @Test
    fun parseval() {
        val n = 4096
        val rnd = Random(7)
        val x = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val re = DoubleArray(n / 2 + 1)
        val im = DoubleArray(n / 2 + 1)
        RealFft(n).forward(x, re, im)
        val time = x.sumOf { it * it }
        var freq = re[0] * re[0] + re[n / 2] * re[n / 2]
        for (k in 1 until n / 2) freq += 2 * (re[k] * re[k] + im[k] * im[k])
        freq /= n
        assertTrue(abs(freq - time) / time < 1e-3, "time $time vs freq $freq")
    }

    @Test
    fun matchesNaiveDft() {
        val n = 64
        val rnd = Random(11)
        val x = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val re = DoubleArray(n / 2 + 1)
        val im = DoubleArray(n / 2 + 1)
        RealFft(n).forward(x, re, im)
        for (k in 0..n / 2) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                r += x[t] * cos(2 * PI * k * t / n)
                i -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(r, re[k], 1e-9, "re[$k]")
            assertEquals(i, im[k], 1e-9, "im[$k]")
        }
    }
}
