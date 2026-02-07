package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsTables

/**
 * Sine oscillator utilities using LUT lookup.
 * Ported from plaits/dsp/oscillator/sine_oscillator.h.
 */
object SineOscillator {
    private const val SINE_LUT_SIZE = 512f
    private const val SINE_LUT_BITS = 9
    private const val MAX_UINT32 = 4294967296.0f

    /** Sine from normalized phase [0, 1+), wrapping. */
    fun sine(phase: Float): Float =
        PlaitsDsp.interpolateWrap(PlaitsTables.SINE, phase, SINE_LUT_SIZE)

    /** Sine from normalized phase [0, 1.25), no wrapping. */
    fun sineNoWrap(phase: Float): Float =
        PlaitsDsp.interpolate(PlaitsTables.SINE, phase, SINE_LUT_SIZE)

    /**
     * Phase-modulated sine using uint32 phase accumulator.
     * PM index range: ±32.
     * Ported from plaits SinePM.
     */
    fun sinePM(phase: Int, pm: Float): Float {
        val maxIndex = 32
        val offset = maxIndex.toFloat()
        val scale = MAX_UINT32 / (maxIndex * 2).toFloat()

        // Add PM offset to phase. All arithmetic wraps as unsigned 32-bit.
        val pmPhase = phase + ((pm + offset) * scale).toInt() * maxIndex * 2

        // Extract table index and fractional part
        val integral = pmPhase ushr (32 - SINE_LUT_BITS)
        val fractional = (pmPhase shl SINE_LUT_BITS).toUInt().toFloat() / MAX_UINT32

        val a = PlaitsTables.SINE[integral]
        val b = PlaitsTables.SINE[integral + 1]
        return a + (b - a) * fractional
    }
}
