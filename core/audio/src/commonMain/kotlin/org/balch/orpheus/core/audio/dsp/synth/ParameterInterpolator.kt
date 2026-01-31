package org.balch.orpheus.core.audio.dsp.synth
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

/**
 * Simple parameter interpolator for smooth value transitions.
 * 
 * Ported from Mutable Instruments' stmlib.
 * Provides linear interpolation of parameter values across a block
 * to prevent clicks and discontinuities.
 */
class ParameterInterpolator {
    private var value: Float = 0f
    private var increment: Float = 0f
    
    /**
     * Initialize the interpolator with a target value and block size.
     * @param currentValue The current stored value
     * @param targetValue The value to interpolate towards
     * @param blockSize The number of samples to interpolate over
     */
    fun init(currentValue: Float, targetValue: Float, blockSize: Int): Float {
        value = currentValue
        increment = (targetValue - currentValue) / blockSize
        return currentValue
    }
    
    /**
     * Get the next interpolated value.
     */
    fun next(): Float {
        val result = value
        value += increment
        return result
    }
    
    /**
     * Get the final target value (after all samples processed).
     */
    fun finalValue(): Float = value
    
    companion object {
        /**
         * Coefficient for one-pole smoothing filter.
         * @param cutoffHz Cutoff frequency in Hz
         * @param sampleRate Sample rate
         */
        fun onePoleCoeff(cutoffHz: Float, sampleRate: Float): Float {
            return 1f - kotlin.math.exp(-2f * kotlin.math.PI.toFloat() * cutoffHz / sampleRate)
        }
    }
}

/**
 * One-pole smoother for parameter values.
 * Provides exponential smoothing for continuous parameter updates.
 */
class OnePoleSmoother(
    private var coeff: Float = 0.001f
) {
    private var state: Float = 0f
    
    /**
     * Initialize with starting value.
     */
    fun init(value: Float) {
        state = value
    }
    
    /**
     * Set the smoothing coefficient.
     * Lower values = slower smoothing.
     */
    fun setCoeff(coeff: Float) {
        this.coeff = coeff.coerceIn(0.0001f, 1f)
    }
    
    /**
     * Process a target value and return the smoothed value.
     */
    fun process(target: Float): Float {
        state += coeff * (target - state)
        return state
    }
    
    /**
     * Get current state without processing.
     */
    fun value(): Float = state
    
    /**
     * Immediately set state to target (no smoothing).
     */
    fun immediate(target: Float) {
        state = target
    }
}
