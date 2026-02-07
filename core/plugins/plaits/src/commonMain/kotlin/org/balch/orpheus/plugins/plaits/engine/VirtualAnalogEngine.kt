// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/virtual_analog_engine.cc (VA_VARIANT=2)

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.VariableSawOscillator
import org.balch.orpheus.plugins.plaits.dsp.VariableShapeOscillator

/**
 * VA_VARIANT=2: Variable square (timbre) + Variable saw (morph).
 * OUT = square + saw. AUX = monster sync (not used).
 */
class VirtualAnalogEngine : PlaitsEngine {
    override val id = PlaitsEngineId.VIRTUAL_ANALOG
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val primary = VariableShapeOscillator()
    private val auxiliary = VariableShapeOscillator()
    private val sync = VariableShapeOscillator()
    private val variableSaw = VariableSawOscillator()

    private var auxiliaryAmount = 0f
    private var xmodAmount = 0f
    private val tempBuffer = FloatArray(MAX_BLOCK_SIZE)

    override fun init() {
        primary.init()
        auxiliary.init()
        auxiliary.setMasterPhase(0.25f)
        sync.init()
        variableSaw.init()
        auxiliaryAmount = 0f
        xmodAmount = 0f
    }

    override fun reset() {}

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val syncAmount = params.timbre * params.timbre
        val auxiliaryDetune = computeDetuning(params.harmonics)
        val primaryF = PlaitsDsp.noteToFrequency(params.note)
        val auxiliaryF = PlaitsDsp.noteToFrequency(params.note + auxiliaryDetune)
        val primarySyncF = PlaitsDsp.noteToFrequency(params.note + syncAmount * 48f)
        val auxiliarySyncF = PlaitsDsp.noteToFrequency(params.note + auxiliaryDetune + syncAmount * 48f)

        var shape = params.morph * 1.5f
        shape = shape.coerceIn(0f, 1f)

        var pw = 0.5f + (params.morph - 0.66f) * 1.46f
        pw = pw.coerceIn(0.5f, 0.995f)

        // Render double varishape to OUT
        var squarePw = 1.3f * params.timbre - 0.15f
        squarePw = squarePw.coerceIn(0.005f, 0.5f)

        val squareSyncRatio = if (params.timbre < 0.5f) 0f
        else (params.timbre - 0.5f) * (params.timbre - 0.5f) * 4f * 48f

        val squareGain = (params.timbre * 8f).coerceAtMost(1f)

        var sawPw = if (params.morph < 0.5f) params.morph + 0.5f
        else 1f - (params.morph - 0.5f) * 2f
        sawPw *= 1.1f
        sawPw = sawPw.coerceIn(0.005f, 1f)

        var sawShape = 10f - 21f * params.morph
        sawShape = sawShape.coerceIn(0f, 1f)

        var sawGain = 8f * (1f - params.morph)
        sawGain = sawGain.coerceIn(0.02f, 1f)

        val squareSyncF = PlaitsDsp.noteToFrequency(params.note + squareSyncRatio)

        sync.renderSync(primaryF, squareSyncF, squarePw, 1f, tempBuffer, 0, size)
        variableSaw.render(auxiliaryF, sawPw, sawShape, out, 0, size)

        val norm = 1f / maxOf(squareGain, sawGain)

        val squareGainMod = PlaitsDsp.ParameterInterpolator(auxiliaryAmount, squareGain * 0.3f * norm, size)
        val sawGainMod = PlaitsDsp.ParameterInterpolator(xmodAmount, sawGain * 0.5f * norm, size)
        auxiliaryAmount = squareGain * 0.3f * norm
        xmodAmount = sawGain * 0.5f * norm

        for (i in 0 until size) {
            out[i] = out[i] * sawGainMod.next() + squareGainMod.next() * tempBuffer[i]
        }

        return false
    }

    private fun computeDetuning(detune: Float): Float {
        var d = 2.05f * detune - 1.025f
        d = d.coerceIn(-1f, 1f)

        val sign = if (d < 0f) -1f else 1f
        d = d * sign * 3.9999f
        val integral = d.toInt()
        val fractional = d - integral

        val a = INTERVALS[integral]
        val b = INTERVALS[integral + 1]
        return (a + (b - a) * squash(squash(fractional))) * sign
    }

    companion object {
        private const val MAX_BLOCK_SIZE = 24
        private val INTERVALS = floatArrayOf(0f, 7.01f, 12.01f, 19.01f, 24.01f)

        private fun squash(x: Float): Float = x * x * (3f - 2f * x)
    }
}
