// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/particle_engine.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.Diffuser
import org.balch.orpheus.plugins.plaits.dsp.Particle
import kotlin.math.abs
import kotlin.math.min

private const val NUM_PARTICLES = 6

/**
 * Filtered random pulses: random impulse trains through resonant bandpass filters
 * with allpass diffuser post-processing.
 *
 * Parameter mapping:
 * - note → center frequency
 * - timbre → density (particle density)
 * - morph → Q/resonance (>0.5) and diffusion (<0.5)
 * - harmonics → frequency spread between particles
 */
class ParticleEngine : PlaitsEngine {
    override val id = PlaitsEngineId.PARTICLE
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val particles = Array(NUM_PARTICLES) { Particle() }
    private val diffuser = Diffuser()
    private val postFilter = SynthDsp.StateVariableFilter()

    override fun init() {
        for (p in particles) p.init()
        diffuser.init()
        postFilter.init()
    }

    override fun reset() {
        diffuser.reset()
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)
        val densitySqrt = PlaitsDsp.noteToFrequency(
            60.0f + params.timbre * params.timbre * 72.0f
        )
        val density = densitySqrt * densitySqrt * (1.0f / NUM_PARTICLES)
        val gain = 1.0f / density
        val qSqrt = PlaitsDsp.semitonesToRatio(
            if (params.morph >= 0.5f) (params.morph - 0.5f) * 120.0f else 0.0f
        )
        val q = 0.5f + qSqrt * qSqrt
        val spread = 48.0f * params.harmonics * params.harmonics
        val rawDiffusionSqrt = 2.0f * abs(params.morph - 0.5f)
        val rawDiffusion = rawDiffusionSqrt * rawDiffusionSqrt
        val diffusion = if (params.morph < 0.5f) rawDiffusion else 0.0f
        val sync = (params.trigger.bits and 1) != 0

        // Zero output buffers
        for (i in 0 until size) out[i] = 0f
        val auxBuf = aux ?: FloatArray(size)
        for (i in 0 until size) auxBuf[i] = 0f

        // Render all particles
        for (p in particles) {
            p.render(sync, density, gain, f0, spread, q, out, auxBuf, size)
        }

        // Post LP filter
        postFilter.setFq(min(f0, 0.49f), 0.5f)
        for (i in 0 until size) {
            out[i] = postFilter.processLp(out[i])
        }

        // Apply diffuser
        diffuser.process(
            0.8f * diffusion * diffusion,
            0.5f * diffusion + 0.25f,
            out,
            size
        )

        // Copy aux back if provided
        if (aux != null && aux !== auxBuf) {
            auxBuf.copyInto(aux, 0, 0, size)
        }

        return false
    }
}
