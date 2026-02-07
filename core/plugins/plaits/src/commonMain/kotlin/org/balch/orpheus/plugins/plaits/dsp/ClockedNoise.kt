package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

/**
 * Band-limited sample-and-hold noise at a target frequency.
 * Uses PolyBLEP anti-aliasing for smooth transitions.
 * Ported from plaits/dsp/noise/clocked_noise.h.
 */
class ClockedNoise {
    private var phase: Float = 0f
    private var sample: Float = 0f
    private var nextSample: Float = 0f
    private var frequency: Float = 0.001f
    private val random = PlaitsDsp.Random()

    fun init() {
        phase = 0f
        sample = 0f
        nextSample = 0f
        frequency = 0.001f
    }

    fun render(sync: Boolean, frequency: Float, out: FloatArray, offset: Int, size: Int) {
        val freq = frequency.coerceIn(0f, 1f)
        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, freq, size)
        this.frequency = freq

        var nextSample = this.nextSample
        var sample = this.sample

        if (sync) {
            phase = 1.0f
        }

        for (i in 0 until size) {
            var thisSample = nextSample
            nextSample = 0f

            val f = fm.next()
            val rawSample = random.getFloat() * 2.0f - 1.0f
            var rawAmount = 4.0f * (f - 0.25f)
            rawAmount = rawAmount.coerceIn(0f, 1f)

            phase += f

            if (phase >= 1.0f) {
                phase -= 1.0f
                val t = phase / f
                val newSample = rawSample
                val discontinuity = newSample - sample
                thisSample += discontinuity * PlaitsDsp.thisBlepSample(t)
                nextSample += discontinuity * PlaitsDsp.nextBlepSample(t)
                sample = newSample
            }
            nextSample += sample
            out[offset + i] = thisSample + rawAmount * (rawSample - thisSample)
        }
        this.nextSample = nextSample
        this.sample = sample
    }
}
