// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/physical_modelling/resonator.h + resonator.cc

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsTables
import kotlin.math.PI
import kotlin.math.tan

private const val MAX_NUM_MODES = 24
private const val MODE_BATCH_SIZE = 4

/**
 * 24-mode resonator bank using batched SVF bandpass filters.
 * Taken from Rings' code but with fixed position.
 */
class PlaitsResonator {
    private var resolution = MAX_NUM_MODES
    private val modeAmplitude = FloatArray(MAX_NUM_MODES)
    private val modeFilters = Array(MAX_NUM_MODES / MODE_BATCH_SIZE) { ResonatorSvf4() }

    fun init(position: Float, resolution: Int) {
        this.resolution = resolution.coerceAtMost(MAX_NUM_MODES)

        // Cosine oscillator for mode amplitudes (approximate)
        var y0 = kotlin.math.cos(2.0 * kotlin.math.PI * position.toDouble()).toFloat()
        var y1 = kotlin.math.sin(2.0 * kotlin.math.PI * position.toDouble()).toFloat()
        val w = 2f * kotlin.math.cos(2.0 * kotlin.math.PI * position.toDouble()).toFloat()
        // Actually use a proper cosine oscillator: CosineOscillator approximate
        // freq = position, Init: iir_coefficient = 2 * cos(2*pi*f), start = cos(phase), y1=0.5
        val iirCoeff = 2f * kotlin.math.cos(2.0 * kotlin.math.PI * position.toDouble()).toFloat()
        var cosState0 = 1f  // cos(0)
        var cosState1 = 0.5f

        for (i in 0 until this.resolution) {
            // CosineOscillator::Next() = s0 = iir*state0 - state1; state1=state0; state0=s0; return s0
            val s0 = iirCoeff * cosState0 - cosState1
            cosState1 = cosState0
            cosState0 = s0
            modeAmplitude[i] = s0 * 0.25f
        }

        for (filter in modeFilters) {
            filter.init()
        }
    }

    fun process(
        f0: Float,
        structure: Float,
        brightness: Float,
        damping: Float,
        input: FloatArray,
        inputOffset: Int,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        val stiffness = PlaitsDsp.interpolate(PlaitsTables.LUT_STIFFNESS, structure, 64f)
        val compensatedF0 = f0 * nthHarmonicCompensation(3, stiffness)

        var harmonic = compensatedF0
        var stretchFactor = 1f
        var currentStiffness = stiffness
        val qSqrt = SynthDsp.semitonesToRatio(damping * 79.7f)
        var q = 500f * qSqrt * qSqrt
        var adjBrightness = brightness * (1f - structure * 0.3f) * (1f - damping * 0.3f)
        val qLoss = adjBrightness * (2f - adjBrightness) * 0.85f + 0.15f

        val modeF = FloatArray(MODE_BATCH_SIZE)
        val modeQ = FloatArray(MODE_BATCH_SIZE)
        val modeA = FloatArray(MODE_BATCH_SIZE)
        var batchCounter = 0
        var batchIndex = 0

        for (i in 0 until resolution) {
            var modeFrequency = harmonic * stretchFactor
            if (modeFrequency >= 0.499f) modeFrequency = 0.499f
            val modeAttenuation = 1f - modeFrequency * 2f

            modeF[batchCounter] = modeFrequency
            modeQ[batchCounter] = 1f + modeFrequency * q
            modeA[batchCounter] = modeAmplitude[i] * modeAttenuation
            batchCounter++

            if (batchCounter == MODE_BATCH_SIZE) {
                batchCounter = 0
                modeFilters[batchIndex].processBandpassAdd(
                    modeF, modeQ, modeA,
                    input, inputOffset, out, outOffset, size
                )
                batchIndex++
            }

            stretchFactor += currentStiffness
            currentStiffness *= if (currentStiffness < 0f) 0.93f else 0.98f
            harmonic += compensatedF0
            q *= qLoss
        }
    }

    private fun nthHarmonicCompensation(n: Int, stiffness: Float): Float {
        var sf = 1f
        var s = stiffness
        for (i in 0 until n - 1) {
            sf += s
            s *= if (s < 0f) 0.93f else 0.98f
        }
        return 1f / sf
    }
}

/**
 * Batched SVF processing 4 bandpass modes simultaneously.
 */
private class ResonatorSvf4 {
    private val state1 = FloatArray(MODE_BATCH_SIZE)
    private val state2 = FloatArray(MODE_BATCH_SIZE)

    fun init() {
        state1.fill(0f)
        state2.fill(0f)
    }

    fun processBandpassAdd(
        f: FloatArray,
        q: FloatArray,
        gain: FloatArray,
        input: FloatArray,
        inputOffset: Int,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        // Precompute filter coefficients
        val g = FloatArray(MODE_BATCH_SIZE)
        val rPlusG = FloatArray(MODE_BATCH_SIZE)
        val h = FloatArray(MODE_BATCH_SIZE)
        val s1 = FloatArray(MODE_BATCH_SIZE)
        val s2 = FloatArray(MODE_BATCH_SIZE)
        val gains = FloatArray(MODE_BATCH_SIZE)

        for (i in 0 until MODE_BATCH_SIZE) {
            g[i] = tan(PI.toFloat() * f[i])
            val r = 1f / q[i]
            h[i] = 1f / (1f + r * g[i] + g[i] * g[i])
            rPlusG[i] = r + g[i]
            s1[i] = state1[i]
            s2[i] = state2[i]
            gains[i] = gain[i]
        }

        for (j in 0 until size) {
            val sIn = input[inputOffset + j]
            var sOut = 0f
            for (i in 0 until MODE_BATCH_SIZE) {
                val hp = (sIn - rPlusG[i] * s1[i] - s2[i]) * h[i]
                val bp = g[i] * hp + s1[i]
                s1[i] = g[i] * hp + bp
                val lp = g[i] * bp + s2[i]
                s2[i] = g[i] * bp + lp
                sOut += gains[i] * bp  // bandpass mode
            }
            out[outOffset + j] += sOut
        }

        for (i in 0 until MODE_BATCH_SIZE) {
            state1[i] = s1[i]
            state2[i] = s2[i]
        }
    }
}
