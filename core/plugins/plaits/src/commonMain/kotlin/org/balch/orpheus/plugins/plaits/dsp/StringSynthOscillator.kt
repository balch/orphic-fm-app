// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/string_synth_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

/**
 * Divide-down organ oscillator: 4 bandlimited sawtooths from a single phase counter.
 * Produces 7 harmonic registers: Saw 8', Square 8', Saw 4', Square 4', Saw 2', Square 2', Saw 1'.
 * Square waveforms are obtained algebraically from sawtooths.
 * Ported from plaits StringSynthOscillator.
 */
class StringSynthOscillator {
    private var phase = 0.0f
    private var nextSample = 0.0f
    private var segment = 0

    private var frequency = 0.001f
    private var saw8Gain = 0.0f
    private var saw4Gain = 0.0f
    private var saw2Gain = 0.0f
    private var saw1Gain = 0.0f

    fun init() {
        phase = 0.0f
        nextSample = 0.0f
        segment = 0
        frequency = 0.001f
        saw8Gain = 0.0f
        saw4Gain = 0.0f
        saw2Gain = 0.0f
        saw1Gain = 0.0f
    }

    /**
     * Render additively into output buffer.
     * @param frequency Base frequency (will be multiplied by 8 internally)
     * @param unshiftedRegistration 7-element array of harmonic register amplitudes
     * @param gain Overall gain scaling
     * @param out Output buffer to add into
     * @param size Number of samples
     */
    fun render(
        frequency: Float,
        unshiftedRegistration: FloatArray,
        gain: Float,
        out: FloatArray,
        size: Int
    ) {
        var freq = frequency * 8.0f

        // Shift down octaves if frequency is too high
        var shift = 0
        while (freq > 0.5f) {
            shift += 2
            freq *= 0.5f
        }
        if (shift >= 8) return

        // Apply shift to registration
        val registration = FloatArray(7)
        for (i in 0 until shift) registration[i] = 0.0f
        for (i in shift until 7) registration[i] = unshiftedRegistration[i - shift]

        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, freq, size)
        val s8gm = PlaitsDsp.ParameterInterpolator(
            saw8Gain, (registration[0] + 2.0f * registration[1]) * gain, size
        )
        val s4gm = PlaitsDsp.ParameterInterpolator(
            saw4Gain, (registration[2] - registration[1] + 2.0f * registration[3]) * gain, size
        )
        val s2gm = PlaitsDsp.ParameterInterpolator(
            saw2Gain, (registration[4] - registration[3] + 2.0f * registration[5]) * gain, size
        )
        val s1gm = PlaitsDsp.ParameterInterpolator(
            saw1Gain, (registration[6] - registration[5]) * gain, size
        )
        this.frequency = freq
        this.saw8Gain = (registration[0] + 2.0f * registration[1]) * gain
        this.saw4Gain = (registration[2] - registration[1] + 2.0f * registration[3]) * gain
        this.saw2Gain = (registration[4] - registration[3] + 2.0f * registration[5]) * gain
        this.saw1Gain = (registration[6] - registration[5]) * gain

        var ph = phase
        var ns = nextSample
        var seg = segment

        for (i in 0 until size) {
            var thisSample = ns
            ns = 0.0f

            val f = fm.next()
            val s8g = s8gm.next()
            val s4g = s4gm.next()
            val s2g = s2gm.next()
            val s1g = s1gm.next()

            ph += f
            val nextSeg = ph.toInt()
            if (nextSeg != seg) {
                var discontinuity = 0.0f
                var ns2 = nextSeg
                if (ns2 == 8) {
                    ph -= 8.0f
                    ns2 -= 8
                    discontinuity -= s8g
                }
                if ((ns2 and 3) == 0) discontinuity -= s4g
                if ((ns2 and 1) == 0) discontinuity -= s2g
                discontinuity -= s1g

                if (discontinuity != 0.0f) {
                    val fraction = ph - ns2.toFloat()
                    val t = fraction / f
                    thisSample += PlaitsDsp.thisBlepSample(t) * discontinuity
                    ns += PlaitsDsp.nextBlepSample(t) * discontinuity
                }
                seg = ns2
            }

            ns += (ph - 4.0f) * s8g * 0.125f
            ns += (ph - (seg and 4).toFloat() - 2.0f) * s4g * 0.25f
            ns += (ph - (seg and 6).toFloat() - 1.0f) * s2g * 0.5f
            ns += (ph - (seg and 7).toFloat() - 0.5f) * s1g
            out[i] += 2.0f * thisSample
        }
        nextSample = ns
        phase = ph
        segment = seg
    }
}
