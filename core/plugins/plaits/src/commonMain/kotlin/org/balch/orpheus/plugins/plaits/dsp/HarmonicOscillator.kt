// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/harmonic_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

/**
 * Harmonic oscillator based on Chebyshev polynomial recurrence.
 * Renders [numHarmonics] partials in a single pass.
 *
 * @param numHarmonics number of harmonics this instance renders (e.g. 12)
 */
class HarmonicOscillator(private val numHarmonics: Int) {
    private var phase = 0f
    private var frequency = 0f
    private val amplitude = FloatArray(numHarmonics)

    fun init() {
        phase = 0f
        frequency = 0f
        amplitude.fill(0f)
    }

    /**
     * Render harmonics starting at [firstHarmonicIndex].
     * If firstHarmonicIndex == 1, output is written (replaces). Otherwise it's added.
     */
    fun render(
        firstHarmonicIndex: Int,
        frequency: Float,
        amplitudes: FloatArray,
        amplitudesOffset: Int,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        val f = frequency.coerceAtMost(0.5f)

        val am = Array(numHarmonics) { i ->
            val hf = f * (firstHarmonicIndex + i).toFloat()
            val clampedHf = if (hf >= 0.5f) 0.5f else hf
            val targetAmp = amplitudes[amplitudesOffset + i] * (1f - clampedHf * 2f)
            PlaitsDsp.ParameterInterpolator(amplitude[i], targetAmp, size).also {
                amplitude[i] = targetAmp
            }
        }

        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, f, size)
        this.frequency = f

        for (i in 0 until size) {
            phase += fm.next()
            if (phase >= 1f) phase -= 1f

            val twoX = 2f * SineOscillator.sineNoWrap(phase)
            var previous: Float
            var current: Float

            if (firstHarmonicIndex == 1) {
                previous = 1f
                current = twoX * 0.5f
            } else {
                val k = firstHarmonicIndex.toFloat()
                previous = SineOscillator.sine(phase * (k - 1f) + 0.25f)
                current = SineOscillator.sine(phase * k)
            }

            var sum = 0f
            for (h in 0 until numHarmonics) {
                sum += am[h].next() * current
                val temp = current
                current = twoX * current - previous
                previous = temp
            }

            if (firstHarmonicIndex == 1) {
                out[outOffset + i] = sum
            } else {
                out[outOffset + i] += sum
            }
        }
    }
}
