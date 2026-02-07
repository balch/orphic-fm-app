package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsTables
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.SineOscillator
import org.balch.orpheus.plugins.plaits.dsp.SlopeOscillator
import kotlin.math.abs

/**
 * Slope → Waveshaper → Wavefolder engine.
 * Ported from plaits/dsp/engine/waveshaping_engine.cc.
 *
 * Parameter mapping:
 * - note → oscillator pitch
 * - timbre → wavefolder gain / overtone amount
 * - morph → pulse width (slope asymmetry)
 * - harmonics → waveshaper curve selection
 */
class WaveshapingEngine : PlaitsEngine {
    override val id = PlaitsEngineId.WAVESHAPING
    override val displayName = id.displayName
    override val alreadyEnveloped = false

    private val slope = SlopeOscillator()
    private val triangle = SlopeOscillator()

    private var previousShape: Float = 0f
    private var previousWavefolderGain: Float = 0f
    private var previousOvertoneGain: Float = 0f

    override fun init() {
        slope.init()
        triangle.init()
        previousShape = 0f
        previousWavefolderGain = 0f
        previousOvertoneGain = 0f
    }

    override fun reset() {}

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)
        val pw = params.morph * 0.45f + 0.5f

        // Start from bandlimited slope signal
        slope.render(f0, pw, out, 0, size)
        val auxBuf = aux ?: FloatArray(size)
        triangle.render(f0, 0.5f, auxBuf, 0, size)

        // Estimate spectral richness for anti-aliasing attenuation
        val slopeRichness = 3.0f + abs(params.morph - 0.5f) * 5.0f
        val shapeAmount = abs(params.harmonics - 0.5f) * 2.0f
        val shapeAmountAttenuation = tame(f0, slopeRichness, 16.0f)
        val wavefolderGain = params.timbre
        val wavefolderGainAttenuation = tame(
            f0,
            slopeRichness * (3.0f + shapeAmount * shapeAmountAttenuation * 5.0f),
            12.0f
        )

        // Apply waveshaper / wavefolder with parameter interpolation
        val shapeModulation = PlaitsDsp.ParameterInterpolator(
            previousShape,
            0.5f + (params.harmonics - 0.5f) * shapeAmountAttenuation,
            size
        )
        val wfGainModulation = PlaitsDsp.ParameterInterpolator(
            previousWavefolderGain,
            0.03f + 0.46f * wavefolderGain * wavefolderGainAttenuation,
            size
        )
        val overtoneGain = params.timbre * (2.0f - params.timbre)
        val overtoneGainModulation = PlaitsDsp.ParameterInterpolator(
            previousOvertoneGain,
            overtoneGain * (2.0f - overtoneGain),
            size
        )

        previousShape = 0.5f + (params.harmonics - 0.5f) * shapeAmountAttenuation
        previousWavefolderGain = 0.03f + 0.46f * wavefolderGain * wavefolderGainAttenuation
        previousOvertoneGain = overtoneGain * (2.0f - overtoneGain)

        for (i in 0 until size) {
            var shape = shapeModulation.next() * 3.9999f
            val shapeIntegral = shape.toInt().coerceIn(0, 4)
            val shapeFractional = shape - shapeIntegral

            val shape1 = PlaitsTables.WAVESHAPER_TABLES[shapeIntegral]
            val shape2 = PlaitsTables.WAVESHAPER_TABLES[shapeIntegral + 1]

            // Map oscillator output [-1, 1] to waveshaper table index [0, 256]
            var wsIndex = 127.0f * out[i] + 128.0f
            val wsIntegral = wsIndex.toInt() and 255
            val wsFractional = wsIndex - wsIndex.toInt()

            // Interpolate between two waveshaper curves
            val x0 = shape1[wsIntegral]
            val x1 = shape1[wsIntegral + 1]
            val x = x0 + (x1 - x0) * wsFractional

            val y0 = shape2[wsIntegral]
            val y1 = shape2[wsIntegral + 1]
            val y = y0 + (y1 - y0) * wsFractional

            val mix = x + (y - x) * shapeFractional

            // Apply wavefolder (tableOffset=1 emulates C++ pointer shift: lut_fold + 1)
            val index = mix * wfGainModulation.next() + 0.5f
            val fold = PlaitsDsp.interpolateHermite(
                PlaitsTables.FOLD, index, 512.0f, tableOffset = 1
            )
            val fold2 = -PlaitsDsp.interpolateHermite(
                PlaitsTables.FOLD_2, index, 512.0f, tableOffset = 1
            )

            // aux output: sine → fold2 crossfade
            val sine = SineOscillator.sine(auxBuf[i] * 0.25f + 0.5f)
            out[i] = fold
            auxBuf[i] = sine + (fold2 - sine) * overtoneGainModulation.next()
        }

        // Copy auxBuf back if aux was null (no-op if aux == auxBuf)
        if (aux != null && aux !== auxBuf) {
            auxBuf.copyInto(aux, 0, 0, size)
        }

        return false
    }

    companion object {
        /**
         * Anti-aliasing gain reduction based on fundamental frequency and harmonic order.
         * Ported from plaits Tame function.
         */
        private fun tame(f0: Float, harmonics: Float, order: Float): Float {
            val f = f0 * harmonics
            val maxF = 0.5f / order
            var maxAmount = 1.0f - (f - maxF) / (0.5f - maxF)
            maxAmount = maxAmount.coerceIn(0f, 1f)
            return maxAmount * maxAmount * maxAmount
        }
    }
}
