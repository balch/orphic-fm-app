// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/additive_engine.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.HarmonicOscillator
import org.balch.orpheus.plugins.plaits.dsp.SineOscillator
import kotlin.math.abs

private const val HARMONIC_BATCH_SIZE = 12
private const val NUM_HARMONICS = 36  // 24 integer + 12 organ (padded)
private const val NUM_HARMONIC_OSCILLATORS = 3

/**
 * Additive synthesis with 24 integer harmonics (OUT) and 8 organ harmonics (AUX, not used).
 * We render only the 24 integer harmonics for Orpheus mono output.
 */
class AdditiveEngine : PlaitsEngine {
    override val id = PlaitsEngineId.ADDITIVE
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val harmonicOscillators = Array(NUM_HARMONIC_OSCILLATORS) { HarmonicOscillator(HARMONIC_BATCH_SIZE) }
    private val amplitudes = FloatArray(NUM_HARMONICS)

    override fun init() {
        for (osc in harmonicOscillators) osc.init()
        amplitudes.fill(0f)
    }

    override fun reset() {
        amplitudes.fill(0f)
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)

        val centroid = params.timbre
        val rawBumps = params.harmonics
        val rawSlope = (1f - 0.6f * rawBumps) * params.morph
        val slope = 0.01f + 1.99f * rawSlope * rawSlope * rawSlope
        val bumps = 16f * rawBumps * rawBumps

        // Update amplitudes for 24 integer harmonics
        updateAmplitudes(centroid, slope, bumps, amplitudes, 0, INTEGER_HARMONICS, 24)

        // Render harmonics 1-12
        harmonicOscillators[0].render(1, f0, amplitudes, 0, out, 0, size)
        // Render harmonics 13-24 (adds to out)
        harmonicOscillators[1].render(13, f0, amplitudes, 12, out, 0, size)

        return false
    }

    private fun updateAmplitudes(
        centroid: Float,
        slope: Float,
        bumps: Float,
        amplitudes: FloatArray,
        ampOffset: Int,
        harmonicIndices: IntArray,
        numHarmonics: Int
    ) {
        val n = (numHarmonics - 1).toFloat()
        val margin = (1f / slope - 1f) / (1f + bumps)
        val center = centroid * (n + margin) - 0.5f * margin

        var sum = 0.001f

        for (i in 0 until numHarmonics) {
            val order = abs(i.toFloat() - center) * slope
            var gain = 1f - order
            gain += abs(gain)  // max(0, gain) * 2
            gain *= gain       // squared

            val b = 0.25f + order * bumps
            val bumpFactor = 1f + SineOscillator.sine(b)

            gain *= bumpFactor
            gain *= gain  // ^4
            gain *= gain  // ^8 (actually the C++ does gain*gain twice after bump, so ^4 total of the bumped value)

            val j = harmonicIndices[i]

            // ONE_POLE smoothing
            amplitudes[ampOffset + j] += 0.001f * (gain - amplitudes[ampOffset + j])
            sum += amplitudes[ampOffset + j]
        }

        // Normalize
        val invSum = 1f / sum
        for (i in 0 until numHarmonics) {
            amplitudes[ampOffset + harmonicIndices[i]] *= invSum
        }
    }

    companion object {
        private val INTEGER_HARMONICS = IntArray(24) { it }
    }
}
