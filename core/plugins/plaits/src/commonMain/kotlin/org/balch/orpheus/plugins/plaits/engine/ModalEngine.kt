// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/modal_engine.cc + modal_voice.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.PlaitsResonator
import kotlin.math.min

private const val MAX_NUM_MODES = 24

/**
 * Modal synthesis: mallet exciter → 24-mode SVF resonator bank.
 * The resonance decay IS the envelope, so alreadyEnveloped = true.
 */
class ModalEngine : PlaitsEngine {
    override val id = PlaitsEngineId.MODAL
    override val displayName = id.displayName
    override val alreadyEnveloped = true
    override val outGain = 0.3f

    private val voice = ModalVoice()
    private val tempBuffer = FloatArray(MAX_BLOCK_SIZE)
    private var harmonicsLp = 0f

    override fun init() {
        harmonicsLp = 0f
        voice.init()
    }

    override fun reset() {
        voice.init()
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        // Zero output
        for (i in 0 until size) out[i] = 0f

        // ONE_POLE smooth harmonics
        harmonicsLp += 0.01f * (params.harmonics - harmonicsLp)

        val sustain = (params.trigger.bits and 2) != 0
        val trigger = (params.trigger.bits and 1) != 0

        voice.render(
            sustain = sustain,
            trigger = trigger,
            accent = params.accent,
            f0 = PlaitsDsp.noteToFrequency(params.note),
            structure = harmonicsLp,
            brightness = params.timbre,
            damping = params.morph,
            temp = tempBuffer,
            out = out,
            outOffset = 0,
            size = size
        )

        return true  // already enveloped
    }

    companion object {
        private const val MAX_BLOCK_SIZE = 24
    }
}

/**
 * Single modal voice: excitation filter → resonator bank.
 */
private class ModalVoice {
    // Use a simple SVF for excitation filtering (single mode, not batched)
    private val excitationFilter = SynthDsp.StateVariableFilter()
    private val resonator = PlaitsResonator()
    private val random = PlaitsDsp.Random()

    fun init() {
        excitationFilter.init()
        resonator.init(0.015f, MAX_NUM_MODES)
    }

    fun render(
        sustain: Boolean,
        trigger: Boolean,
        accent: Float,
        f0: Float,
        structure: Float,
        brightness: Float,
        damping: Float,
        temp: FloatArray,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        val density = brightness * brightness
        val adjBrightness = brightness + 0.25f * accent * (1f - brightness)
        val adjDamping = damping + 0.25f * accent * (1f - damping)

        val range = if (sustain) 36f else 60f
        val f = if (sustain) 4f * f0 else 2f * f0
        val cutoff = min(
            f * SynthDsp.semitonesToRatio((adjBrightness * (2f - adjBrightness) - 0.5f) * range),
            0.499f
        )
        val q = if (sustain) 0.7f else 1.5f

        // Set excitation filter
        excitationFilter.setFq(cutoff, q)

        // Synthesize excitation signal
        if (sustain) {
            val dustF = 0.00005f + 0.99995f * density * density
            for (i in 0 until size) {
                temp[i] = PlaitsDsp.dust(random, dustF) * (4f - dustF * 3f) * accent
            }
        } else {
            temp.fill(0f, 0, size)
            if (trigger) {
                val attenuation = 1f - adjDamping * 0.5f
                val amplitude = (0.12f + 0.08f * accent) * attenuation
                temp[0] = amplitude * SynthDsp.semitonesToRatio(cutoff * cutoff * 24f) / cutoff
            }
        }

        // LP filter excitation
        for (i in 0 until size) {
            temp[i] = excitationFilter.processLp(temp[i])
        }

        // Feed into resonator
        resonator.process(f0, structure, adjBrightness, adjDamping, temp, 0, out, outOffset, size)
    }
}
