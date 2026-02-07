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
     * Soft saturation curve to prevent hard clipping.
     * Linear below 0.5, tanh-like saturation above.
     */
    fun softClip(x: Float): Float {
        val absX = if (x < 0) -x else x
        return if (absX < 0.5f) {
            x
        } else {
            val sign = if (x < 0) -1f else 1f
            // Approximated tanh for performance: (x - 0.5) / (1 + (x-0.5)^2) + 0.5
            val offset = absX - 0.5f
            val saturated = 0.5f + offset / (1.0f + offset * offset)
            sign * saturated.coerceAtMost(1.0f)
        }
    }

    /**
     * Zero-delay-feedback State Variable Filter.
     * Ported from stmlib/dsp/filter.h
     */
    class StateVariableFilter {
        var g: Float = 0f
            private set
        var r: Float = 0f
            private set
        var h: Float = 0f
            private set
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
         * Set frequency and resonance using a polynomial tan approximation.
         * More efficient than [setFq] for per-sample use.
         * Ported from stmlib set_f_q<FREQUENCY_ACCURATE>.
         */
        fun setFqAccurate(f: Float, resonance: Float) {
            val freq = f.coerceIn(0f, 0.497f)
            val wc = PI_F * freq
            val wc2 = wc * wc
            g = wc * (1f + wc2 * (0.3333314f + wc2 * (0.1333924f +
                wc2 * (0.0533741f + wc2 * (0.0029005f + wc2 * 0.0095168f)))))
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

        /**
         * Continuous LP→BP→HP morph. mode: 0=LP, 0.5=BP, 1.0=HP.
         * Ported from stmlib Svf::ProcessMultimodeLPtoHP.
         */
        fun processMultimode(input: Float, mode: Float): Float {
            val hp = (input - r * state1 - g * state1 - state2) * h
            val bp = g * hp + state1
            state1 = g * hp + bp
            val lp = g * bp + state2
            state2 = g * bp + lp
            val lpGain = maxOf(1.0f - mode * 2.0f, 0.0f)
            val bpGain = 1.0f - 2.0f * kotlin.math.abs(mode - 0.5f)
            val hpGain = minOf(-mode * 2.0f + 1.0f, 0.0f)
            return lpGain * lp + bpGain * bp + hpGain * hp
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
    
    /**
     * Damping filter for Karplus-Strong string synthesis.
     * Ported from rings/dsp/string.h DampingFilter.
     * 
     * Uses a simple FIR lowpass for frequency-dependent damping
     * with interpolated coefficients for smooth parameter changes.
     */
    class DampingFilter {
        private var x_: Float = 0f
        private var x__: Float = 0f
        private var brightness: Float = 0f
        private var brightnessIncrement: Float = 0f
        private var damping: Float = 0f
        private var dampingIncrement: Float = 0f
        
        fun init() {
            x_ = 0f
            x__ = 0f
            brightness = 0f
            brightnessIncrement = 0f
            damping = 0f
            dampingIncrement = 0f
        }
        
        fun reset() {
            x_ = 0f
            x__ = 0f
        }
        
        /**
         * Configure damping and brightness with optional interpolation.
         * @param damping Damping coefficient (0-1)
         * @param brightness Brightness (0-1, affects lowpass cutoff)
         * @param size Block size for interpolation (0 = immediate)
         */
        fun configure(damping: Float, brightness: Float, size: Int = 0) {
            if (size == 0) {
                this.damping = damping
                this.brightness = brightness
                dampingIncrement = 0f
                brightnessIncrement = 0f
            } else {
                val step = 1f / size.toFloat()
                dampingIncrement = (damping - this.damping) * step
                brightnessIncrement = (brightness - this.brightness) * step
            }
        }
        
        /**
         * Process one sample through the damping filter.
         */
        fun process(x: Float): Float {
            val h0 = (1f + brightness) * 0.5f
            val h1 = (1f - brightness) * 0.25f
            val y = damping * (h0 * x_ + h1 * (x + x__))
            x__ = x_
            x_ = x
            brightness += brightnessIncrement
            damping += dampingIncrement
            return y
        }
    }
}
