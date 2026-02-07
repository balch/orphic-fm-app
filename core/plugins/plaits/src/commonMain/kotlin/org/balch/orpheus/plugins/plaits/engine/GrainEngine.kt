// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/grain_engine.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.GrainletOscillator
import kotlin.math.max

/**
 * Grain synthesis: two grainlet oscillators at different frequency ratios,
 * summed through a DC blocker.
 */
class GrainEngine : PlaitsEngine {
    override val id = PlaitsEngineId.GRAIN
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val grainlet = Array(2) { GrainletOscillator() }

    // DC blocker state (one-pole high-pass)
    private var dcState1 = 0f
    private var dcState2 = 0f

    // Temp buffer for second grainlet
    private val auxBuf = FloatArray(MAX_BLOCK_SIZE)

    override fun init() {
        grainlet[0].init()
        grainlet[1].init()
        dcState1 = 0f
        dcState2 = 0f
    }

    override fun reset() {}

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val root = params.note
        val f0 = PlaitsDsp.noteToFrequency(root)

        val f1 = PlaitsDsp.noteToFrequency(24f + 84f * params.timbre)
        val ratio = SynthDsp.semitonesToRatio(-24f + 48f * params.harmonics)
        val carrierBleed = if (params.harmonics < 0.5f) 1f - 2f * params.harmonics else 0f
        val carrierBleedFixed = carrierBleed * (2f - carrierBleed)
        val carrierShape = 0.33f + (params.morph - 0.33f) * max(1f - f0 * 24f, 0f)

        grainlet[0].render(f0, f1, carrierShape, carrierBleedFixed, out, 0, size)
        grainlet[1].render(f0, f1 * ratio, carrierShape, carrierBleedFixed, auxBuf, 0, size)

        // DC blocker (one-pole high-pass) on summed grainlets
        val dcF = 0.3f * f0
        // set_f<FREQUENCY_DIRTY> approximation: g ≈ f * pi (for small f)
        val dcG = (dcF * kotlin.math.PI.toFloat()).coerceAtMost(0.497f * kotlin.math.PI.toFloat())

        for (i in 0 until size) {
            val input = out[i] + auxBuf[i]
            // One-pole HP: y = input - state; state += g * y
            val hp = input - dcState1
            dcState1 += dcG * hp
            out[i] = hp
        }

        return false
    }

    companion object {
        private const val MAX_BLOCK_SIZE = 24
    }
}
