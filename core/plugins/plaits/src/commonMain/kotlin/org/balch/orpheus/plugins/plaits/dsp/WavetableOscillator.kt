// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/wavetable_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsWavetables
import kotlin.math.min

private const val WAVETABLE_SIZE = 128
private const val WAVETABLE_SIZE_F = 128.0f
private const val NUM_WAVES = 15
private const val MAX_FREQUENCY = 0.25f

/**
 * Differentiator: computes the derivative of an integrated waveform.
 * Converts integrated (cumulative sum) wavetable data back to waveform.
 * Ported from plaits Differentiator.
 */
class Differentiator {
    private var previous = 0.0f
    private var lp = 0.0f

    fun init() {
        previous = 0.0f
        lp = 0.0f
    }

    fun process(coefficient: Float, s: Float): Float {
        // ONE_POLE(lp_, s - previous_, coefficient)
        lp += coefficient * ((s - previous) - lp)
        previous = s
        return lp
    }
}

/**
 * Integrated wavetable synthesis oscillator.
 * Reads from ShortArray wavetable data, interpolates between waveforms,
 * and differentiates the integrated signal.
 *
 * Template params hardcoded: wavetableSize=128, numWaves=15.
 * Ported from plaits WavetableOscillator<128, 15>.
 */
class WavetableOscillator {
    private var phase = 0.0f
    private var frequency = 0.0f
    private var amplitude = 0.0f
    private var waveform = 0.0f
    private var lp = 0.0f
    private val differentiator = Differentiator()

    fun init() {
        phase = 0.0f
        frequency = 0.0f
        amplitude = 0.0f
        waveform = 0.0f
        lp = 0.0f
        differentiator.init()
    }

    /**
     * Render additively into output buffer.
     * @param frequency Oscillator frequency (normalized to sample rate)
     * @param amplitude Output amplitude
     * @param waveform Waveform morph position (0..1 across available waves)
     * @param wavetableOffsets Array of offsets into WAV_INTEGRATED_WAVES for each wave
     * @param out Output buffer to add into
     * @param size Number of samples
     */
    fun render(
        frequency: Float,
        amplitude: Float,
        waveform: Float,
        wavetableOffsets: IntArray,
        out: FloatArray,
        size: Int
    ) {
        var freq = frequency.coerceIn(0.0000001f, MAX_FREQUENCY)
        var amp = amplitude * (1.0f - 2.0f * freq) // attenuate high frequencies
        amp *= 1.0f / (freq * 131072.0f) // approximate scale

        val freqMod = PlaitsDsp.ParameterInterpolator(this.frequency, freq, size)
        val ampMod = PlaitsDsp.ParameterInterpolator(this.amplitude, amp, size)
        val waveMod = PlaitsDsp.ParameterInterpolator(
            this.waveform, waveform * (NUM_WAVES - 1.0001f), size
        )
        this.frequency = freq
        this.amplitude = amp
        this.waveform = waveform * (NUM_WAVES - 1.0001f)

        var lpState = lp
        var ph = phase

        for (i in 0 until size) {
            val f0 = freqMod.next()
            val cutoff = min(WAVETABLE_SIZE_F * f0, 1.0f)

            ph += f0
            if (ph >= 1.0f) ph -= 1.0f

            val w = waveMod.next()
            val wIntegral = w.toInt()
            val wFractional = w - wIntegral

            val p = ph * WAVETABLE_SIZE_F
            val pIntegral = p.toInt()
            val pFractional = p - pIntegral

            val x0 = interpolateWave(wavetableOffsets[wIntegral], pIntegral, pFractional)
            val x1 = interpolateWave(wavetableOffsets[wIntegral + 1], pIntegral, pFractional)

            val s = differentiator.process(cutoff, (x0 + (x1 - x0) * wFractional))
            // ONE_POLE
            lpState += cutoff * (s - lpState)
            out[i] += ampMod.next() * lpState
        }
        lp = lpState
        phase = ph
    }

    companion object {
        /** Linear interpolation on int16 wavetable data. */
        fun interpolateWave(offset: Int, indexIntegral: Int, indexFractional: Float): Float {
            val data = PlaitsWavetables.WAV_INTEGRATED_WAVES
            val a = data[offset + indexIntegral].toFloat()
            val b = data[offset + indexIntegral + 1].toFloat()
            return a + (b - a) * indexFractional
        }

        /** Hermite interpolation on int16 wavetable data. */
        fun interpolateWaveHermite(offset: Int, indexIntegral: Int, indexFractional: Float): Float {
            val data = PlaitsWavetables.WAV_INTEGRATED_WAVES
            val xm1 = data[offset + indexIntegral].toFloat()
            val x0 = data[offset + indexIntegral + 1].toFloat()
            val x1 = data[offset + indexIntegral + 2].toFloat()
            val x2 = data[offset + indexIntegral + 3].toFloat()
            val c = (x1 - xm1) * 0.5f
            val v = x0 - x1
            val w = c + v
            val a = w + v + (x2 - x0) * 0.5f
            val bNeg = w + a
            val f = indexFractional
            return (((a * f) - bNeg) * f + c) * f + x0
        }
    }
}
