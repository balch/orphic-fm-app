package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.TriggerState
import org.balch.orpheus.plugins.plaits.dsp.ClockedNoise

/**
 * Clocked noise processed by a multimode filter.
 * Ported from plaits/dsp/engine/noise_engine.cc.
 *
 * Parameter mapping:
 * - note → filter cutoff frequency
 * - timbre → noise clock rate
 * - morph → filter resonance
 * - harmonics → filter mode (0=LP, 0.5=BP, 1=HP) AND secondary filter offset
 */
class NoiseEngine : PlaitsEngine {
    override val id = PlaitsEngineId.NOISE
    override val displayName = id.displayName
    override val alreadyEnveloped = false

    private val clockedNoise = Array(2) { ClockedNoise() }
    private val lpHpFilter = SynthDsp.StateVariableFilter()
    private val bpFilter = Array(2) { SynthDsp.StateVariableFilter() }
    private val tempBuffer = FloatArray(MAX_BLOCK_SIZE)

    private var previousF0: Float = 0f
    private var previousF1: Float = 0f
    private var previousQ: Float = 0f
    private var previousMode: Float = 0f

    override fun init() {
        clockedNoise[0].init()
        clockedNoise[1].init()
        lpHpFilter.init()
        bpFilter[0].init()
        bpFilter[1].init()
        previousF0 = 0f
        previousF1 = 0f
        previousQ = 0f
        previousMode = 0f
    }

    override fun reset() {}

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)
        val f1 = PlaitsDsp.noteToFrequency(
            params.note + params.harmonics * 48.0f - 24.0f
        )

        // Clock frequency: when unpatched, lowest note = 0; when patched, -24
        val clockLowestNote = if (
            params.trigger == TriggerState.UNPATCHED ||
            params.trigger == TriggerState.RISING_EDGE_HIGH
        ) 0f else -24f
        val clockF = PlaitsDsp.noteToFrequency(
            params.timbre * (128.0f - clockLowestNote) + clockLowestNote
        )

        val q = 0.5f * SynthDsp.semitonesToRatio(params.morph * 120.0f)

        val sync = params.trigger == TriggerState.RISING_EDGE ||
            params.trigger == TriggerState.RISING_EDGE_HIGH

        // Render two clocked noise sources into out (as temp) and tempBuffer
        val auxBuf = aux ?: FloatArray(size)
        clockedNoise[0].render(sync, clockF, auxBuf, 0, size)
        clockedNoise[1].render(sync, clockF * f1 / f0, tempBuffer, 0, size)

        val f0Mod = PlaitsDsp.ParameterInterpolator(previousF0, f0, size)
        val f1Mod = PlaitsDsp.ParameterInterpolator(previousF1, f1, size)
        val qMod = PlaitsDsp.ParameterInterpolator(previousQ, q, size)
        val modeMod = PlaitsDsp.ParameterInterpolator(previousMode, params.harmonics, size)

        previousF0 = f0
        previousF1 = f1
        previousQ = q
        previousMode = params.harmonics

        for (i in 0 until size) {
            val currentF0 = f0Mod.next()
            val currentF1 = f1Mod.next()
            val currentQ = qMod.next()
            val gain = 1.0f / PlaitsDsp.sqrt((0.5f + currentQ) * 40.0f * currentF0)

            lpHpFilter.setFqAccurate(currentF0, currentQ)
            bpFilter[0].setFqAccurate(currentF0, currentQ)
            bpFilter[1].setFqAccurate(currentF1, currentQ)

            val input1 = auxBuf[i] * gain
            val input2 = tempBuffer[i] * gain

            out[i] = lpHpFilter.processMultimode(input1, modeMod.next())
            aux?.let {
                it[i] = bpFilter[0].processBp(input1) + bpFilter[1].processBp(input2)
            }
        }

        return false
    }

    companion object {
        private const val MAX_BLOCK_SIZE = 24
    }
}
