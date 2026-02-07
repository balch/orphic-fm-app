package org.balch.orpheus.plugins.drum.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.TriggerState

/**
 * Adapter wrapping [FmDrum] to the [PlaitsEngine] interface.
 *
 * Parameter mapping:
 * - note → f0 (frequency, mapped to 0..1 range)
 * - timbre → tone (noise/overdrive)
 * - morph → decay
 * - harmonics → FM amount (p4)
 */
class FmDrumEngine : PlaitsEngine {
    override val id = PlaitsEngineId.FM_DRUM
    override val displayName = id.displayName
    override val alreadyEnveloped = true
    override val outGain = 0.8f

    private val drum = FmDrum(SynthDsp.SAMPLE_RATE)

    override fun init() = drum.init()
    override fun reset() = drum.init() // FmDrum.init() is its reset

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        // Map MIDI note to 0..1 normalized frequency for FmDrum
        val f0 = ((params.note - 24f) / 72f).coerceIn(0f, 1f)
        val trigger = params.trigger == TriggerState.RISING_EDGE ||
                params.trigger == TriggerState.RISING_EDGE_HIGH

        for (i in 0 until size) {
            val trig = trigger && i == 0
            out[i] = drum.process(
                trig = trig,
                accent = params.accent,
                f0 = f0,
                tone = params.timbre,
                decay = params.morph,
                p4 = params.harmonics,
                p5 = 0.5f
            )
        }
        return true
    }
}
