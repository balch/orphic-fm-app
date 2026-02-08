package org.balch.orpheus.plugins.plaits

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

/**
 * Shared DSP primitives for Plaits engine ports.
 * Ported from Mutable Instruments stmlib/dsp and plaits/dsp.
 */
object PlaitsDsp {
    /** A0 reference frequency recalculated for 44100 Hz sample rate. */
    private const val A0 = (440.0f / 8.0f) / SynthDsp.SAMPLE_RATE

    /**
     * Convert MIDI note to normalized frequency (fraction of sample rate).
     * Ported from plaits NoteToFrequency.
     */
    fun noteToFrequency(midiNote: Float): Float {
        val note = (midiNote - 9.0f).coerceIn(-128f, 127f)
        return A0 * 0.25f * SynthDsp.semitonesToRatio(note)
    }

    // --- PolyBLEP (from stmlib/dsp/polyblep.h) ---

    fun thisBlepSample(t: Float): Float = 0.5f * t * t

    fun nextBlepSample(t: Float): Float {
        val t1 = 1.0f - t
        return -0.5f * t1 * t1
    }

    fun nextIntegratedBlepSample(t: Float): Float {
        val t1 = 0.5f * t
        val t2 = t1 * t1
        val t4 = t2 * t2
        return 0.1875f - t1 + 1.5f * t2 - t4
    }

    fun thisIntegratedBlepSample(t: Float): Float =
        nextIntegratedBlepSample(1.0f - t)

    // --- Interpolation (from stmlib/dsp/dsp.h) ---

    /**
     * Linear interpolation on a table. [index] is 0..1, scaled by [size].
     * Ported from stmlib::Interpolate.
     */
    fun interpolate(table: FloatArray, index: Float, size: Float): Float {
        val scaled = index * size
        val integral = scaled.toInt()
        val fractional = scaled - integral
        val a = table[integral]
        val b = table[integral + 1]
        return a + (b - a) * fractional
    }

    /**
     * Linear interpolation with phase wrapping. [index] wraps to [0, size).
     * Ported from stmlib::InterpolateWrap.
     */
    fun interpolateWrap(table: FloatArray, index: Float, size: Float): Float {
        val wrapped = index - index.toInt().toFloat()
        val scaled = wrapped * size
        val integral = scaled.toInt()
        val fractional = scaled - integral
        val a = table[integral]
        val b = table[integral + 1]
        return a + (b - a) * fractional
    }

    /**
     * Hermite interpolation. Table must have guard elements at [-1] and [size+1].
     * Caller passes table offset by +1 so index 0 maps to element [1].
     * [index] is 0..1, scaled by [size].
     * Ported from stmlib::InterpolateHermite.
     */
    fun interpolateHermite(
        table: FloatArray, index: Float, size: Float, tableOffset: Int = 0
    ): Float {
        val scaled = index * size
        val integral = scaled.toInt() + tableOffset
        val f = scaled - (scaled.toInt())
        val xm1 = table[integral - 1]
        val x0 = table[integral]
        val x1 = table[integral + 1]
        val x2 = table[integral + 2]
        val c = (x1 - xm1) * 0.5f
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) * 0.5f
        val bNeg = w + a
        return ((a * f - bNeg) * f + c) * f + x0
    }

    // --- Random (LCG from stmlib/utils/random.h) ---

    class Random {
        private var state: Int = 0x12345678

        fun getWord(): Int {
            state = state * 1664525 + 1013904223
            return state
        }

        fun getFloat(): Float = (getWord().toUInt().toFloat()) / 4294967296.0f
    }

    // --- Parameter interpolation helper ---

    /**
     * Simple linear parameter interpolator for block-based processing.
     * Ported from stmlib ParameterInterpolator.
     */
    class ParameterInterpolator(
        private var value: Float,
        target: Float,
        size: Int
    ) {
        private val increment: Float = (target - value) / size

        fun next(): Float {
            value += increment
            return value
        }
    }

    // --- Noise ---

    /**
     * Sparse impulse generator. Returns a random value when triggered, 0 otherwise.
     * Ported from plaits/dsp/noise/dust.h.
     */
    fun dust(random: Random, frequency: Float): Float {
        val u = random.getFloat()
        return if (u < frequency) u / frequency else 0f
    }

    // --- One-pole filter helper ---

    /** Inline one-pole filter: state = state + coefficient * (input - state). */
    fun onePole(state: Float, input: Float, coefficient: Float): Float =
        state + coefficient * (input - state)

    // --- Utility ---

    /** Convert semitone offset to frequency ratio. Delegates to SynthDsp. */
    fun semitonesToRatio(semitones: Float): Float = SynthDsp.semitonesToRatio(semitones)

    fun sqrt(x: Float): Float = kotlin.math.sqrt(x)

    fun crossfade(a: Float, b: Float, fade: Float): Float = a + (b - a) * fade

    fun softLimit(x: Float): Float = x * (27.0f + x * x) / (27.0f + 9.0f * x * x)

    fun softClip(x: Float): Float = when {
        x < -3.0f -> -1.0f
        x > 3.0f -> 1.0f
        else -> softLimit(x)
    }
}
