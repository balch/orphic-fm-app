package org.balch.orpheus.features.pulsar.bench.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 DFT of a real frame of [n] samples, through one complex FFT of n/2 points.
 * Unscaled, like a textbook DFT. Not thread safe: the work buffers are shared between calls.
 */
class RealFft(val n: Int) {
    init {
        require(n >= 4 && n and (n - 1) == 0) { "n must be a power of two >= 4: $n" }
    }

    private val m = n / 2
    // e^(-2 pi i k / n) for k < n/2; the n/2-point FFT uses every other entry.
    private val cosT = DoubleArray(m) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(m) { -sin(2 * PI * it / n) }
    private val rev = IntArray(m).also { r ->
        val bits = m.countTrailingZeroBits()
        for (j in 0 until m) {
            var x = j
            var y = 0
            repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[j] = y
        }
    }
    private val zr = DoubleArray(m)
    private val zi = DoubleArray(m)

    /** Writes bins 0..n/2 of the DFT of the first [n] samples of [input] into [re] and [im]. */
    fun forward(input: DoubleArray, re: DoubleArray, im: DoubleArray) {
        for (j in 0 until m) {
            val r = rev[j]
            zr[r] = input[2 * j]
            zi[r] = input[2 * j + 1]
        }
        var size = 2
        while (size <= m) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < m) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]
                    val wi = sinT[j * step]
                    val a = start + j
                    val b = a + half
                    val tr = zr[b] * wr - zi[b] * wi
                    val ti = zr[b] * wi + zi[b] * wr
                    zr[b] = zr[a] - tr
                    zi[b] = zi[a] - ti
                    zr[a] += tr
                    zi[a] += ti
                }
                start += size
            }
            size *= 2
        }
        // Split the packed even/odd spectra: X[k] = E[k] + W^k O[k].
        re[0] = zr[0] + zi[0]
        im[0] = 0.0
        re[m] = zr[0] - zi[0]
        im[m] = 0.0
        for (k in 1 until m) {
            val ar = zr[k]
            val ai = zi[k]
            val br = zr[m - k]
            val bi = -zi[m - k]
            val er = (ar + br) * 0.5
            val ei = (ai + bi) * 0.5
            val or = (ai - bi) * 0.5
            val oi = (br - ar) * 0.5
            val wr = cosT[k]
            val wi = sinT[k]
            re[k] = er + or * wr - oi * wi
            im[k] = ei + or * wi + oi * wr
        }
    }
}
