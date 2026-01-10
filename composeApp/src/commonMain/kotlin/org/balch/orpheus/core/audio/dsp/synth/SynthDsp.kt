package org.balch.orpheus.core.audio.dsp.synth

import kotlin.math.pow
import kotlin.math.tan

/**
 * Core DSP utilities ported from Mutable Instruments stmlib.
 */
object SynthDsp {
    const val SAMPLE_RATE = 44100.0f
    const val PI_F = 3.1415926535f

    /**
     * Pitch conversion: semitones to frequency ratio.
     * Ported from stmlib/dsp/units.h
     */
    fun semitonesToRatio(semitones: Float): Float {
        return 2.0f.pow(semitones / 12.0f)
    }

    /**
     * Zero-delay-feedback State Variable Filter.
     * Ported from stmlib/dsp/filter.h
     */
    class StateVariableFilter {
        private var g: Float = 0f
        private var r: Float = 0f
        private var h: Float = 0f
        private var state1: Float = 0f
        private var state2: Float = 0f

        fun init() {
            setFq(0.01f, 100.0f)
            reset()
        }

        fun reset() {
            state1 = 0f
            state2 = 0f
        }

        /**
         * Set frequency and resonance.
         * f is normalized frequency [0, 0.5]
         */
        fun setFq(f: Float, resonance: Float) {
            val frequency = f.coerceIn(0f, 0.497f)
            g = tan(PI_F * frequency)
            r = 1.0f / resonance
            h = 1.0f / (1.0f + r * g + g * g)
        }

        /**
         * Process a single sample and return LP/BP/HP/NP
         */
        fun process(input: Float): FilterOutputs {
            val hp = (input - r * state1 - g * state1 - state2) * h
            val bp = g * hp + state1
            state1 = g * hp + bp
            val lp = g * bp + state2
            state2 = g * bp + lp
            
            return FilterOutputs(lp, bp, hp)
        }

        /**
         * Process for specific mode to avoid allocations
         */
        fun processLp(input: Float): Float {
            val hp = (input - r * state1 - g * state1 - state2) * h
            val bp = g * hp + state1
            state1 = g * hp + bp
            val lp = g * bp + state2
            state2 = g * bp + lp
            return lp
        }

        fun processBp(input: Float): Float {
            val hp = (input - r * state1 - g * state1 - state2) * h
            val bp = g * hp + state1
            state1 = g * hp + bp
            val lp = g * bp + state2
            state2 = g * bp + lp
            return bp
        }

        fun processHp(input: Float): Float {
            val hp = (input - r * state1 - g * state1 - state2) * h
            val bp = g * hp + state1
            state1 = g * hp + bp
            val lp = g * bp + state2
            state2 = g * bp + lp
            return hp
        }

        data class FilterOutputs(val lp: Float, val bp: Float, val hp: Float)
    }

    /**
     * One-pole smoothing filter.
     * Ported from stmlib/dsp/filter.h
     */
    class OnePoleFilter {
        private var g: Float = 0f
        private var state: Float = 0f

        fun init() {
            setF(0.01f)
            reset()
        }

        fun reset() {
            state = 0f
        }

        /**
         * Set normalized frequency [0, 0.5]
         */
        fun setF(f: Float) {
            val frequency = f.coerceIn(0f, 0.497f)
            g = tan(PI_F * frequency)
            g /= (1.0f + g)
        }

        /**
         * Set coefficient directly for very fast smoothing
         */
        fun setCoefficient(c: Float) {
            g = c
        }

        fun process(input: Float): Float {
            state += g * (input - state)
            return state
        }
    }
}
