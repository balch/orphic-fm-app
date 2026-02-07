package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsTables
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.Downsampler4x
import org.balch.orpheus.plugins.plaits.dsp.SineOscillator

/**
 * Classic 2-operator FM synthesis engine.
 * Ported from plaits/dsp/engine/fm_engine.cc.
 *
 * Parameter mapping:
 * - note → carrier pitch (shifted -24 for 4x oversampling headroom)
 * - timbre → FM index (modulation amount)
 * - morph → feedback (negative = modulator PM feedback, positive = modulator self-feedback)
 * - harmonics → FM ratio (quantized to musical intervals)
 */
class FmEngine : PlaitsEngine {
    override val id = PlaitsEngineId.FM
    override val displayName = id.displayName
    override val alreadyEnveloped = false

    // Phase accumulators (wrapping 32-bit integer arithmetic)
    private var carrierPhase: Int = 0
    private var modulatorPhase: Int = 0
    private var subPhase: Int = 0

    // Previous values for parameter interpolation
    private var previousCarrierFrequency: Float = A0
    private var previousModulatorFrequency: Float = A0
    private var previousAmount: Float = 0f
    private var previousFeedback: Float = 0f
    private var previousSample: Float = 0f

    // Downsampler FIR state
    private var carrierFir: Float = 0f
    private var subFir: Float = 0f

    override fun init() {
        carrierPhase = 0
        modulatorPhase = 0
        subPhase = 0
        previousCarrierFrequency = A0
        previousModulatorFrequency = A0
        previousAmount = 0f
        previousFeedback = 0f
        previousSample = 0f
        carrierFir = 0f
        subFir = 0f
    }

    override fun reset() {}

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        // 4x oversampling — shift note down by 24 semitones
        val note = params.note - 24.0f

        // Look up FM ratio from quantizer table
        val ratio = PlaitsDsp.interpolate(
            PlaitsTables.FM_FREQUENCY_QUANTIZER,
            params.harmonics,
            128.0f
        )

        val modulatorNote = note + ratio
        var targetModulatorFrequency = PlaitsDsp.noteToFrequency(modulatorNote)
        targetModulatorFrequency = targetModulatorFrequency.coerceIn(0f, 0.5f)

        // Reduce FM index for high pitched notes to prevent aliasing
        var hfTaming = 1.0f - (modulatorNote - 72.0f) * 0.025f
        hfTaming = hfTaming.coerceIn(0f, 1f)
        hfTaming *= hfTaming

        val carrierFrequency = PlaitsDsp.ParameterInterpolator(
            previousCarrierFrequency,
            PlaitsDsp.noteToFrequency(note),
            size
        )
        val modulatorFrequency = PlaitsDsp.ParameterInterpolator(
            previousModulatorFrequency,
            targetModulatorFrequency,
            size
        )
        val amountModulation = PlaitsDsp.ParameterInterpolator(
            previousAmount,
            2.0f * params.timbre * params.timbre * hfTaming,
            size
        )
        val feedbackModulation = PlaitsDsp.ParameterInterpolator(
            previousFeedback,
            2.0f * params.morph - 1.0f,
            size
        )

        // Save targets for next block
        previousCarrierFrequency = PlaitsDsp.noteToFrequency(note)
        previousModulatorFrequency = targetModulatorFrequency
        previousAmount = 2.0f * params.timbre * params.timbre * hfTaming
        previousFeedback = 2.0f * params.morph - 1.0f

        for (i in 0 until size) {
            val amount = amountModulation.next()
            val feedback = feedbackModulation.next()
            val phaseFeedback = if (feedback < 0f) 0.5f * feedback * feedback else 0f
            val carrierIncrement = (MAX_UINT32 * carrierFrequency.next()).toInt()
            val modFreq = modulatorFrequency.next()

            val carrierDownsampler = Downsampler4x(carrierFir)
            val subDownsampler = Downsampler4x(subFir)

            for (j in 0 until OVERSAMPLING) {
                modulatorPhase += (MAX_UINT32 *
                    modFreq * (1.0f + previousSample * phaseFeedback)).toInt()
                carrierPhase += carrierIncrement
                subPhase += carrierIncrement ushr 1

                val modulatorFb = if (feedback > 0f) 0.25f * feedback * feedback else 0f
                val modulator = SineOscillator.sinePM(
                    modulatorPhase, modulatorFb * previousSample
                )
                val carrier = SineOscillator.sinePM(
                    carrierPhase, amount * modulator
                )
                val sub = SineOscillator.sinePM(
                    subPhase, amount * carrier * 0.25f
                )
                // ONE_POLE: previousSample += 0.05 * (carrier - previousSample)
                previousSample += 0.05f * (carrier - previousSample)

                carrierDownsampler.accumulate(j, carrier)
                subDownsampler.accumulate(j, sub)
            }

            out[i] = carrierDownsampler.read()
            aux?.let { it[i] = subDownsampler.read() }
            carrierFir = carrierDownsampler.state()
            subFir = subDownsampler.state()
        }

        return false
    }

    companion object {
        private const val A0 = (440.0f / 8.0f) / 44100.0f
        private const val MAX_UINT32 = 4294967296.0f
        private const val OVERSAMPLING = 4
    }
}
