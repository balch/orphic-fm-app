// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/speech/lpc_speech_synth_controller.h + .cc

package org.balch.orpheus.plugins.plaits.speech

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsSpeechData

/**
 * Orchestrates LPC speech synth playback with time-stretching and formant shifting.
 * Handles both word bank playback (triggered) and free-running phoneme scanning.
 * Uses BLEP-based sample-rate conversion to decouple formant shift from playback rate.
 */
class LPCSpeechSynthController {
    private var clockPhase = 0.0f
    private val sample = FloatArray(2)
    private val nextSample = FloatArray(2)
    private var gain = 0.0f
    private val synth = LPCSpeechSynth()

    private var playbackFrame = -1
    private var lastPlaybackFrame = -1
    private var remainingFrameSamples = 0
    private var lastBank = -2 // sentinel: never matches initial state

    lateinit var wordBank: LPCSpeechSynthWordBank
        private set

    fun init(wordBank: LPCSpeechSynthWordBank) {
        this.wordBank = wordBank
        clockPhase = 0.0f
        playbackFrame = -1
        lastPlaybackFrame = -1
        remainingFrameSamples = 0
        lastBank = -2
        sample.fill(0.0f)
        nextSample.fill(0.0f)
        gain = 0.0f
        synth.init()
    }

    fun render(
        freeRunning: Boolean,
        trigger: Boolean,
        bank: Int,
        frequency: Float,
        prosodyAmount: Float,
        speed: Float,
        address: Float,
        formantShift: Float,
        gain: Float,
        excitation: FloatArray,
        output: FloatArray,
        size: Int
    ) {
        val rateRatio = PlaitsDsp.semitonesToRatio((formantShift - 0.5f) * 36.0f)
        val rate = rateRatio / 6.0f

        // All utterances normalized for average f0 of 100 Hz
        val pitchShift = frequency /
            (rateRatio * PlaitsSpeechData.LPC_DEFAULT_F0 / CORRECTED_SAMPLE_RATE)
        val timeStretch = PlaitsDsp.semitonesToRatio(
            -speed * 24.0f +
                if (formantShift < 0.4f) (formantShift - 0.4f) * -45.0f
                else if (formantShift > 0.6f) (formantShift - 0.6f) * -45.0f
                else 0.0f
        )

        // Reset playback state when switching between word bank and phoneme modes
        // to prevent stale frame indices from indexing into a smaller frame list
        if (bank != lastBank) {
            if ((bank == -1) != (lastBank == -1) && lastBank != -2) {
                playbackFrame = -1
                lastPlaybackFrame = -1
                remainingFrameSamples = 0
            }
            lastBank = bank
        }

        if (bank != -1) {
            val resetEverything = wordBank.load(bank)
            if (resetEverything) {
                playbackFrame = -1
                lastPlaybackFrame = -1
            }
        }

        val numFrames = if (bank == -1) {
            PlaitsSpeechData.LPC_NUM_VOWELS
        } else {
            wordBank.numFrames
        }

        val frames = if (bank == -1) {
            PlaitsSpeechData.LPC_PHONEMES
        } else {
            wordBank.frames.asList()
        }

        if (trigger) {
            if (bank == -1) {
                // Pick a pseudo-random consonant
                val r = ((address + 3.0f * formantShift + 7.0f * frequency) * 8.0f).toInt()
                playbackFrame = (r % PlaitsSpeechData.LPC_NUM_CONSONANTS) +
                    PlaitsSpeechData.LPC_NUM_VOWELS
                lastPlaybackFrame = playbackFrame + 1
            } else {
                val (start, end) = wordBank.getWordBoundaries(address)
                playbackFrame = start
                lastPlaybackFrame = end
            }
            remainingFrameSamples = 0
        }

        if (playbackFrame == -1 && remainingFrameSamples == 0) {
            synth.playFrame(
                frames,
                address * (numFrames.toFloat() - 1.0001f),
                true
            )
        } else {
            if (remainingFrameSamples == 0) {
                synth.playFrame(frames, playbackFrame.toFloat(), false)
                remainingFrameSamples = (SAMPLE_RATE / PlaitsSpeechData.LPC_FPS * timeStretch).toInt()
                playbackFrame++
                if (playbackFrame >= lastPlaybackFrame) {
                    val backToScanMode = bank == -1 || freeRunning
                    playbackFrame = if (backToScanMode) -1 else lastPlaybackFrame
                }
            }
            remainingFrameSamples -= minOf(size, remainingFrameSamples)
        }

        val gainModulation = PlaitsDsp.ParameterInterpolator(this.gain, gain, size)
        this.gain = gain

        // Render with BLEP-based sample rate conversion
        val singleExcitation = FloatArray(1)
        val singleOutput = FloatArray(1)

        for (i in 0 until size) {
            val thisSample = floatArrayOf(nextSample[0], nextSample[1])
            nextSample.fill(0.0f)

            clockPhase += rate
            if (clockPhase >= 1.0f) {
                clockPhase -= 1.0f
                val resetTime = clockPhase / rate

                synth.render(prosodyAmount, pitchShift, singleExcitation, 0, singleOutput, 0, 1)
                val newExcitation = singleExcitation[0]
                val newOutput = singleOutput[0]

                val discExcitation = newExcitation - sample[0]
                val discOutput = newOutput - sample[1]
                thisSample[0] += discExcitation * PlaitsDsp.thisBlepSample(resetTime)
                nextSample[0] += discExcitation * PlaitsDsp.nextBlepSample(resetTime)
                thisSample[1] += discOutput * PlaitsDsp.thisBlepSample(resetTime)
                nextSample[1] += discOutput * PlaitsDsp.nextBlepSample(resetTime)
                sample[0] = newExcitation
                sample[1] = newOutput
            }
            nextSample[0] += sample[0]
            nextSample[1] += sample[1]
            val g = gainModulation.next()
            excitation[i] = thisSample[0] * g
            output[i] = thisSample[1] * g
        }
    }

    companion object {
        private const val SAMPLE_RATE = SynthDsp.SAMPLE_RATE
        // Corrected for the actual vs. intended sample rate (Plaits uses 48000, we use 44100)
        private const val CORRECTED_SAMPLE_RATE = SAMPLE_RATE
    }
}
