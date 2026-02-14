// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments stmlib DSP utilities.
// Original: stmlib/dsp/dsp.h, stmlib/dsp/units.h
// License: MIT

package org.balch.orpheus.plugins.flux.engine

import kotlin.math.pow

/** DSP utility functions ported from stmlib. */
object DspUtil {
    /** Linear interpolation into a lookup table.
     *  Equivalent to stmlib::Interpolate(table, index, size).
     *  Index is clamped to [0, 1) to prevent out-of-bounds access. */
    fun interpolate(table: FloatArray, index: Float, size: Float): Float {
        val scaled = (index.coerceIn(0f, 1f) * size).coerceAtMost(size - 1f)
        val integral = scaled.toInt()
        val fractional = scaled - integral
        val a = table[integral]
        val b = table[integral + 1]
        return a + (b - a) * fractional
    }

    /** Linear interpolation with offset into a table subsection. */
    fun interpolate(table: FloatArray, index: Float, size: Float, offset: Int): Float {
        val scaled = (index.coerceIn(0f, 1f) * size).coerceAtMost(size - 1f)
        val integral = scaled.toInt()
        val fractional = scaled - integral
        val a = table[offset + integral]
        val b = table[offset + integral + 1]
        return a + (b - a) * fractional
    }

    /** Linear crossfade between [a] and [b]. */
    fun crossfade(a: Float, b: Float, fade: Float): Float {
        return a + (b - a) * fade
    }

    /** One-pole low-pass filter step (in-place style, returns new state). */
    fun onePole(state: Float, input: Float, coefficient: Float): Float {
        return state + coefficient * (input - state)
    }

    /** Convert semitones to frequency ratio: 2^(semitones/12).
     *  Simple formula; the C++ version uses lookup tables for speed on ARM. */
    fun semitonesToRatio(semitones: Float): Float {
        return 2f.pow(semitones / 12f)
    }

    /** Slope tracking: asymmetric one-pole with separate positive/negative rates. */
    fun slope(state: Float, input: Float, positive: Float, negative: Float): Float {
        val error = input - state
        return state + (if (error > 0f) positive else negative) * error
    }
}
