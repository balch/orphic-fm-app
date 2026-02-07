package org.balch.orpheus.plugins.drum.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.TriggerState

/**
 * Adapter wrapping [AnalogSnareDrum] to the [PlaitsEngine] interface.
 *
 * Parameter mapping:
 * - note → f0 (frequency)
 * - timbre → tone
 * - morph → decay
 * - harmonics → snappy (p4)
 */
class AnalogSnareDrumEngine : PlaitsEngine {
    override val id = PlaitsEngineId.ANALOG_SNARE_DRUM
    override val displayName = id.displayName
    override val alreadyEnveloped = true
    override val outGain = 0.8f

    private val drum = AnalogSnareDrum()

    override fun init() = drum.init()
    override fun reset() = drum.reset()

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        val f0 = noteToF0(params.note)
        val trigger = params.trigger == TriggerState.RISING_EDGE ||
                params.trigger == TriggerState.RISING_EDGE_HIGH

        for (i in 0 until size) {
            val trig = trigger && i == 0
            out[i] = drum.process(
                trigger = trig,
                accent = params.accent,
                f0 = f0,
                tone = params.timbre,
                decay = params.morph,
                snappy = params.harmonics
            )
        }
        return true
    }

    private fun noteToF0(note: Float): Float {
        val freq = 440f * SynthDsp.semitonesToRatio(note - 69f)
        return freq / SynthDsp.SAMPLE_RATE
    }
}
