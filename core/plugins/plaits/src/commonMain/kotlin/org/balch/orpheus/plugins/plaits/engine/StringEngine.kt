// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/string_engine.cc + string_voice.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.PlaitsDelayLine
import org.balch.orpheus.plugins.plaits.dsp.PlaitsString
import kotlin.math.min

private const val NUM_STRINGS = 3

/**
 * Three-voice Karplus-Strong string synthesis with round-robin triggering.
 */
class StringEngine : PlaitsEngine {
    override val id = PlaitsEngineId.STRING
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val voices = Array(NUM_STRINGS) { StringVoice() }
    private val f0 = FloatArray(NUM_STRINGS) { 0.01f }
    private val f0Delay = PlaitsDelayLine(16)
    private var activeString = NUM_STRINGS - 1
    private val tempBuffer = FloatArray(MAX_BLOCK_SIZE)

    override fun init() {
        for (voice in voices) voice.init()
        f0.fill(0.01f)
        f0Delay.reset()
        activeString = NUM_STRINGS - 1
    }

    override fun reset() {
        f0Delay.reset()
        for (voice in voices) voice.reset()
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val risingEdge = (params.trigger.bits and 1) != 0
        val sustain = (params.trigger.bits and 2) != 0

        if (risingEdge) {
            f0[activeString] = f0Delay.read(14f)
            activeString = (activeString + 1) % NUM_STRINGS
        }

        val currentF0 = PlaitsDsp.noteToFrequency(params.note)
        f0[activeString] = currentF0
        f0Delay.write(currentF0)

        // Zero output
        for (i in 0 until size) out[i] = 0f

        for (i in 0 until NUM_STRINGS) {
            voices[i].render(
                sustain = sustain && i == activeString,
                trigger = risingEdge && i == activeString,
                accent = params.accent,
                f0 = f0[i],
                structure = params.harmonics,
                brightness = params.timbre * params.timbre,
                damping = params.morph,
                temp = tempBuffer,
                out = out,
                outOffset = 0,
                size = size
            )
        }

        return false
    }

    companion object {
        private const val MAX_BLOCK_SIZE = 24
    }
}

/**
 * Single string voice: excitation generator + KS string model.
 */
private class StringVoice {
    private val excitationFilter = SynthDsp.StateVariableFilter()
    private val string = PlaitsString()
    private var remainingNoiseSamples = 0
    private val random = PlaitsDsp.Random()

    fun init() {
        excitationFilter.init()
        string.init()
        remainingNoiseSamples = 0
    }

    fun reset() {
        string.reset()
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

        if (trigger || sustain) {
            val range = 72f
            val f = 4f * f0
            val cutoff = min(
                f * SynthDsp.semitonesToRatio((adjBrightness * (2f - adjBrightness) - 0.5f) * range),
                0.499f
            )
            val q = if (sustain) 1f else 0.5f
            remainingNoiseSamples = (1f / f0).toInt()
            excitationFilter.setFq(cutoff, q)
        }

        if (sustain) {
            val dustF = 0.00005f + 0.99995f * density * density
            for (i in 0 until size) {
                temp[i] = PlaitsDsp.dust(random, dustF) * (8f - dustF * 6f) * accent
            }
        } else if (remainingNoiseSamples > 0) {
            val noiseSamples = min(remainingNoiseSamples, size)
            remainingNoiseSamples -= noiseSamples
            for (i in 0 until noiseSamples) {
                temp[i] = 2f * random.getFloat() - 1f
            }
            for (i in noiseSamples until size) {
                temp[i] = 0f
            }
        } else {
            temp.fill(0f, 0, size)
        }

        // LP filter excitation
        for (i in 0 until size) {
            temp[i] = excitationFilter.processLp(temp[i])
        }

        // Compute non-linearity from structure
        val nonLinearity = when {
            structure < 0.24f -> (structure - 0.24f) * 4.166f
            structure > 0.26f -> (structure - 0.26f) * 1.35135f
            else -> 0f
        }

        string.process(f0, nonLinearity, adjBrightness, adjDamping, temp, 0, out, outOffset, size)
    }
}
