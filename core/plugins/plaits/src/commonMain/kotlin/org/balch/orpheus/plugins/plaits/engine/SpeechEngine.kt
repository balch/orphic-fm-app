// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/speech_engine.h + .cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.PlaitsSpeechData
import org.balch.orpheus.plugins.plaits.TriggerState
import org.balch.orpheus.plugins.plaits.dsp.HysteresisQuantizer2
import org.balch.orpheus.plugins.plaits.speech.LPCSpeechSynthController
import org.balch.orpheus.plugins.plaits.speech.LPCSpeechSynthWordBank
import org.balch.orpheus.plugins.plaits.speech.NaiveSpeechSynth
import org.balch.orpheus.plugins.plaits.speech.SAMSpeechSynth

/**
 * Speech synthesis engine blending three methods:
 * - Naive formant synthesis (5 parallel bandpass filters)
 * - SAM (Software Automatic Mouth, consonant/vowel resonance)
 * - LPC-10 vocoder (with 5 pre-encoded word banks)
 *
 * harmonics selects the mode/word bank, morph selects phoneme/word,
 * timbre controls formant shift, trigger starts word playback.
 */
class SpeechEngine : PlaitsEngine {
    override val id = PlaitsEngineId.SPEECH
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.5f

    private val wordBankQuantizer = HysteresisQuantizer2()

    private val naiveSpeechSynth = NaiveSpeechSynth()
    private val samSpeechSynth = SAMSpeechSynth()
    private val lpcSpeechSynthController = LPCSpeechSynthController()
    private val lpcSpeechSynthWordBank = LPCSpeechSynthWordBank()

    private val tempBuffer0 = FloatArray(BLOCK_SIZE)
    private val tempBuffer1 = FloatArray(BLOCK_SIZE)

    var prosodyAmount = 0.0f
    var speed = 0.0f

    override fun init() {
        naiveSpeechSynth.init()
        samSpeechSynth.init()
        lpcSpeechSynthWordBank.init()
        lpcSpeechSynthController.init(lpcSpeechSynthWordBank)
        wordBankQuantizer.init(PlaitsSpeechData.NUM_WORD_BANKS + 1, 0.1f, false)
        prosodyAmount = 0.0f
        speed = 0.0f
    }

    override fun reset() {
        lpcSpeechSynthWordBank.reset()
    }

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)
        val group = params.harmonics * 6.0f

        // Interpolates between 3 models: naive, SAM, LPC
        return if (group <= 2.0f) {
            renderNaiveSamBlend(params, f0, group, out, aux, size)
            false
        } else {
            renderLpcWordBanks(params, f0, group, out, aux, size)
        }
    }

    private fun renderNaiveSamBlend(
        params: EngineParameters,
        f0: Float,
        group: Float,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ) {
        var blend = group
        if (group <= 1.0f) {
            naiveSpeechSynth.render(
                click = params.trigger == TriggerState.RISING_EDGE,
                frequency = f0,
                phoneme = params.morph,
                vocalRegister = params.timbre,
                temp = tempBuffer0,
                excitation = aux ?: tempBuffer0,
                output = out,
                size = size
            )
        } else {
            lpcSpeechSynthController.render(
                freeRunning = params.trigger.bits and TriggerState.UNPATCHED.bits != 0,
                trigger = params.trigger.bits and TriggerState.RISING_EDGE.bits != 0,
                bank = -1,
                frequency = f0,
                prosodyAmount = 0.0f,
                speed = 0.0f,
                address = params.morph,
                formantShift = params.timbre,
                gain = 1.0f,
                excitation = aux ?: tempBuffer0,
                output = out,
                size = size
            )
            blend = 2.0f - blend
        }

        // SAM always rendered, blended in
        samSpeechSynth.render(
            consonant = params.trigger == TriggerState.RISING_EDGE,
            frequency = f0,
            vowel = params.morph,
            formantShift = params.timbre,
            excitation = tempBuffer0,
            output = tempBuffer1,
            size = size
        )

        // Smooth Hermite crossfade (applied twice for smoother transition)
        var b = blend * blend * (3.0f - 2.0f * blend)
        b = b * b * (3.0f - 2.0f * b)
        for (i in 0 until size) {
            aux?.let { it[i] += (tempBuffer0[i] - it[i]) * b }
            out[i] += (tempBuffer1[i] - out[i]) * b
        }
    }

    private fun renderLpcWordBanks(
        params: EngineParameters,
        f0: Float,
        group: Float,
        out: FloatArray,
        aux: FloatArray?,
        size: Int
    ): Boolean {
        val wordBank = wordBankQuantizer.process((group - 2.0f) * 0.275f) - 1

        val replayProsody = wordBank >= 0 &&
            (params.trigger.bits and TriggerState.UNPATCHED.bits == 0)

        lpcSpeechSynthController.render(
            freeRunning = params.trigger.bits and TriggerState.UNPATCHED.bits != 0,
            trigger = params.trigger.bits and TriggerState.RISING_EDGE.bits != 0,
            bank = wordBank,
            frequency = f0,
            prosodyAmount = prosodyAmount,
            speed = speed,
            address = params.morph,
            formantShift = params.timbre,
            gain = if (replayProsody) params.accent else 1.0f,
            excitation = aux ?: tempBuffer0,
            output = out,
            size = size
        )

        return replayProsody
    }

    companion object {
        private const val BLOCK_SIZE = 24 // PLAITS_BLOCK_SIZE
    }
}
