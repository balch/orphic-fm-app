package org.balch.songe.audio.dsp

import kotlin.math.pow
import org.balch.songe.audio.ModSource
import org.balch.songe.audio.SongeEngine
import org.balch.songe.util.Logger

/**
 * Shared implementation of SongeEngine using DSP primitive interfaces.
 * All audio routing logic is platform-independent.
 */
class DspSongeEngine(private val audioEngine: AudioEngine) : SongeEngine {
    
    // 8 Voices with pitch ranges (0.5=bass, 1.0=mid, 2.0=high)
    private val voices = listOf(
        // Pair 1-2: Bass range
        SharedVoice(audioEngine, pitchMultiplier = 0.5),
        SharedVoice(audioEngine, pitchMultiplier = 0.5),
        // Pair 3-4: Mid range
        SharedVoice(audioEngine, pitchMultiplier = 1.0),
        SharedVoice(audioEngine, pitchMultiplier = 1.0),
        // Pair 5-6: Mid range
        SharedVoice(audioEngine, pitchMultiplier = 1.0),
        SharedVoice(audioEngine, pitchMultiplier = 1.0),
        // Pair 7-8: High range (octave up)
        SharedVoice(audioEngine, pitchMultiplier = 2.0),
        SharedVoice(audioEngine, pitchMultiplier = 2.0)
    )

    // Dual Delay & Modulation
    private val delay1 = audioEngine.createDelayLine()
    private val delay2 = audioEngine.createDelayLine()
    private val delay1FeedbackGain = audioEngine.createMultiply()
    private val delay2FeedbackGain = audioEngine.createMultiply()

    // Delay Modulation
    private val hyperLfo = SharedHyperLfo(audioEngine)
    private val delay1ModMixer = audioEngine.createMultiplyAdd() // (LFO * Depth) + BaseTime
    private val delay2ModMixer = audioEngine.createMultiplyAdd()

    // Self-modulation attenuators
    private val selfMod1Attenuator = audioEngine.createMultiply()
    private val selfMod2Attenuator = audioEngine.createMultiply()

    // TOTAL FB: Output -> LFO Frequency Modulation
    private val totalFbGain = audioEngine.createMultiply()

    // Monitoring & FX
    private val peakFollower = audioEngine.createPeakFollower()
    private val limiter = audioEngine.createLimiter()
    private val driveGain = audioEngine.createMultiply()
    private val masterGain = audioEngine.createMultiply()

    // Parallel Clean/Distorted Paths (Lyra MIX)
    private val preDistortionSummer = audioEngine.createAdd()
    private val cleanPathGain = audioEngine.createMultiply()
    private val distortedPathGain = audioEngine.createMultiply()
    private val postMixSummer = audioEngine.createAdd()

    // Dry/Wet Mix for Delays
    private val dryGain = audioEngine.createMultiply()
    private val wetGain = audioEngine.createMultiply()

    // Vibrato (Global pitch wobble)
    private val vibratoLfo = audioEngine.createSineOscillator()
    private val vibratoDepthGain = audioEngine.createMultiply()

    // State caches
    private val quadPitchOffsets = DoubleArray(2) { 0.5 }
    private val voiceTuneCache = DoubleArray(8) { 0.5 }
    private var fmStructureCrossQuad = false

    init {
        // Add all units to audio engine
        audioEngine.addUnit(delay1)
        audioEngine.addUnit(delay2)
        audioEngine.addUnit(delay1FeedbackGain)
        audioEngine.addUnit(delay2FeedbackGain)
        audioEngine.addUnit(delay1ModMixer)
        audioEngine.addUnit(delay2ModMixer)
        audioEngine.addUnit(selfMod1Attenuator)
        audioEngine.addUnit(selfMod2Attenuator)
        audioEngine.addUnit(totalFbGain)
        audioEngine.addUnit(peakFollower)
        audioEngine.addUnit(limiter)
        audioEngine.addUnit(driveGain)
        audioEngine.addUnit(masterGain)
        audioEngine.addUnit(preDistortionSummer)
        audioEngine.addUnit(cleanPathGain)
        audioEngine.addUnit(distortedPathGain)
        audioEngine.addUnit(postMixSummer)
        audioEngine.addUnit(dryGain)
        audioEngine.addUnit(wetGain)
        audioEngine.addUnit(vibratoLfo)
        audioEngine.addUnit(vibratoDepthGain)

        // Setup peak follower
        peakFollower.setHalfLife(0.1)

        // TOTAL FB: PeakFollower -> scaled -> HyperLfo.feedbackInput
        peakFollower.output.connect(totalFbGain.inputA)
        totalFbGain.inputB.set(0.0) // Default: no feedback
        totalFbGain.output.connect(hyperLfo.feedbackInput)

        // Drive/Master defaults
        driveGain.inputB.set(1.0)
        masterGain.inputB.set(0.7)

        // Clean/Distorted Mix defaults (50/50)
        cleanPathGain.inputB.set(0.5)
        distortedPathGain.inputB.set(0.5)

        // Dry/Wet defaults (50/50 mix)
        dryGain.inputB.set(0.5)
        wetGain.inputB.set(0.5)

        // Vibrato LFO setup
        vibratoLfo.frequency.set(5.0) // 5Hz wobble rate
        vibratoLfo.amplitude.set(1.0)
        vibratoLfo.output.connect(vibratoDepthGain.inputA)
        vibratoDepthGain.inputB.set(0.0) // Default: no vibrato

        // Self-modulation attenuator (0.02 = only 2% of audio signal reaches mod input)
        selfMod1Attenuator.inputB.set(0.02)
        selfMod2Attenuator.inputB.set(0.02)
        delay1.output.connect(selfMod1Attenuator.inputA)
        delay2.output.connect(selfMod2Attenuator.inputA)

        // Delay Defaults
        delay1.allocate(110250) // 2.5 seconds max buffer at 44.1kHz
        delay2.allocate(110250)

        // Delay Modulation Wiring
        hyperLfo.output.connect(delay1ModMixer.inputA)
        hyperLfo.output.connect(delay2ModMixer.inputA)
        delay1ModMixer.inputB.set(0.0) // Mod depth
        delay2ModMixer.inputB.set(0.0)
        delay1ModMixer.output.connect(delay1.delay)
        delay2ModMixer.output.connect(delay2.delay)

        // ROUTING
        // Delays -> WetGain -> PreDistortionSummer
        delay1.output.connect(wetGain.inputA)
        delay2.output.connect(wetGain.inputA)
        wetGain.output.connect(preDistortionSummer.inputA)

        // DryGain -> PreDistortionSummer
        dryGain.output.connect(preDistortionSummer.inputB)

        // Split into Clean and Distorted paths
        preDistortionSummer.output.connect(cleanPathGain.inputA)
        cleanPathGain.output.connect(postMixSummer.inputA)

        preDistortionSummer.output.connect(driveGain.inputA)
        driveGain.output.connect(limiter.input)
        limiter.output.connect(distortedPathGain.inputA)
        distortedPathGain.output.connect(postMixSummer.inputB)

        // PostMixSummer -> Master Gain
        postMixSummer.output.connect(masterGain.inputA)

        // Master Gain -> LineOut & Monitor
        masterGain.output.connect(audioEngine.lineOutLeft)
        masterGain.output.connect(audioEngine.lineOutRight)
        masterGain.output.connect(peakFollower.input)

        // Independent Feedback Loops per Delay
        delay1.output.connect(delay1FeedbackGain.inputA)
        delay1FeedbackGain.output.connect(delay1.input)
        delay2.output.connect(delay2FeedbackGain.inputA)
        delay2FeedbackGain.output.connect(delay2.input)

        // Wire voices to audio paths
        voices.forEach { voice ->
            // VOICES -> DELAYS (wet path)
            voice.output.connect(delay1.input)
            voice.output.connect(delay2.input)

            // VOICES -> DRY GAIN (dry path)
            voice.output.connect(dryGain.inputA)

            // VIBRATO -> Voice frequency modulation
            vibratoDepthGain.output.connect(voice.vibratoInput)
            voice.vibratoDepth.set(1.0) // Pass through engine-scaled vibrato

            // COUPLING default depth
            voice.couplingDepth.set(0.0)
        }

        // Wire voice coupling: Each voice's envelope -> partner's coupling input
        for (pairIndex in 0 until 4) {
            val voiceA = voices[pairIndex * 2]
            val voiceB = voices[pairIndex * 2 + 1]
            voiceA.envelopeOutput.connect(voiceB.couplingInput)
            voiceB.envelopeOutput.connect(voiceA.couplingInput)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SongeEngine Implementation
    // ═══════════════════════════════════════════════════════════

    override fun start() {
        if (audioEngine.isRunning) return
        Logger.info { "Starting Shared Audio Engine..." }
        audioEngine.start()
        Logger.info { "Audio Engine Started" }
    }

    override fun stop() {
        Logger.info { "Stopping Audio Engine..." }
        audioEngine.stop()
        Logger.info { "Audio Engine Stopped" }
    }

    override fun setDrive(amount: Float) {
        val driveVal = 1.0 + (amount * 14.0) // Reduced from 49 for warmer saturation
        limiter.drive.set(driveVal)
    }

    override fun setDistortionMix(amount: Float) {
        val distortedLevel = amount
        val cleanLevel = 1.0f - amount
        cleanPathGain.inputB.set(cleanLevel.toDouble())
        distortedPathGain.inputB.set(distortedLevel.toDouble())
    }

    override fun setMasterVolume(amount: Float) {
        masterGain.inputB.set(amount.toDouble())
    }

    override fun setDelayTime(index: Int, time: Float) {
        val delaySeconds = 0.01 + (time * 1.99)
        if (index == 0) {
            delay1ModMixer.inputC.set(delaySeconds)
        } else {
            delay2ModMixer.inputC.set(delaySeconds)
        }
    }

    override fun setDelayFeedback(amount: Float) {
        val fb = amount * 0.95
        delay1FeedbackGain.inputB.set(fb)
        delay2FeedbackGain.inputB.set(fb)
    }

    override fun setDelayMix(amount: Float) {
        val wetLevel = amount
        val dryLevel = 1.0f - amount
        dryGain.inputB.set(dryLevel.toDouble())
        wetGain.inputB.set(wetLevel.toDouble())
    }

    override fun setDelayModDepth(index: Int, amount: Float) {
        val depth = amount * 0.5
        if (index == 0) {
            delay1ModMixer.inputB.set(depth)
        } else {
            delay2ModMixer.inputB.set(depth)
        }
    }

    override fun setDelayModSource(index: Int, isLfo: Boolean) {
        val targetMixer = if (index == 0) delay1ModMixer else delay2ModMixer
        targetMixer.inputA.disconnectAll()
        
        if (isLfo) {
            hyperLfo.output.connect(targetMixer.inputA)
        } else {
            val attenuatedSelf = if (index == 0) selfMod1Attenuator.output else selfMod2Attenuator.output
            attenuatedSelf.connect(targetMixer.inputA)
        }
    }

    override fun setDelayLfoWaveform(isTriangle: Boolean) {
        hyperLfo.setTriangleMode(isTriangle)
    }

    @Deprecated("Use granular setDelayTime/Feedback instead")
    override fun setDelay(time: Float, feedback: Float) {
        setDelayTime(0, time)
        setDelayTime(1, time)
        setDelayFeedback(feedback)
    }

    override fun setHyperLfoFreq(index: Int, frequency: Float) {
        val freqHz = 0.01 + (frequency * 10.0)
        if (index == 0) {
            hyperLfo.frequencyA.set(freqHz)
        } else {
            hyperLfo.frequencyB.set(freqHz)
        }
    }

    override fun setHyperLfoMode(andMode: Boolean) {
        hyperLfo.setMode(andMode)
    }

    override fun setHyperLfoLink(active: Boolean) {
        hyperLfo.setLink(active)
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        voiceTuneCache[index] = tune.toDouble()
        updateVoiceFrequency(index)
    }

    private fun updateVoiceFrequency(index: Int) {
        val tune = voiceTuneCache[index]
        val quadIndex = index / 4
        val quadPitch = quadPitchOffsets[quadIndex]
        
        // Base frequency range: 55Hz - 880Hz (4 octaves)
        val baseFreq = 55.0 * 2.0.pow(tune * 4.0)
        
        // Apply quad pitch offset (-1 to +1 octave)
        val pitchMultiplier = 2.0.pow((quadPitch - 0.5) * 2.0)
        val finalFreq = baseFreq * pitchMultiplier
        
        voices[index].frequency.set(finalFreq)
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        voices[index].gate.set(if (active) 1.0 else 0.0)
    }

    override fun setVoiceFeedback(index: Int, amount: Float) {
        // Not implemented in shared engine yet
    }

    override fun setVoiceFmDepth(index: Int, amount: Float) {
        voices[index].fmDepth.set(amount.toDouble())
    }

    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        voices[index].setEnvelopeSpeed(speed)
    }

    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1
        voices[voiceA].sharpness.set(sharpness.toDouble())
        voices[voiceB].sharpness.set(sharpness.toDouble())
    }

    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        quadPitchOffsets[quadIndex] = pitch.toDouble()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            updateVoiceFrequency(i)
        }
    }

    override fun setQuadHold(quadIndex: Int, amount: Float) {
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setHoldLevel(amount.toDouble())
        }
    }

    override fun setDuoModSource(duoIndex: Int, source: ModSource) {
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1
        
        voices[voiceA].modInput.disconnectAll()
        voices[voiceB].modInput.disconnectAll()
        
        when (source) {
            ModSource.OFF -> { /* Already disconnected */ }
            ModSource.LFO -> {
                hyperLfo.output.connect(voices[voiceA].modInput)
                hyperLfo.output.connect(voices[voiceB].modInput)
            }
            ModSource.VOICE_FM -> {
                if (fmStructureCrossQuad) {
                    // Cross-quad routing
                    when (duoIndex) {
                        0 -> { // Duo 0 (voices 0-1) receives from Duo 3 (voices 6-7)
                            voices[6].output.connect(voices[voiceA].modInput)
                            voices[7].output.connect(voices[voiceB].modInput)
                        }
                        1 -> { // Duo 1 (voices 2-3): Within-pair
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        2 -> { // Duo 2 (voices 4-5) receives from Duo 1 (voices 2-3)
                            voices[2].output.connect(voices[voiceA].modInput)
                            voices[3].output.connect(voices[voiceB].modInput)
                        }
                        3 -> { // Duo 3 (voices 6-7): Within-pair
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                    }
                } else {
                    // Within-Pair Routing (default)
                    voices[voiceA].output.connect(voices[voiceB].modInput)
                    voices[voiceB].output.connect(voices[voiceA].modInput)
                }
            }
        }
    }

    override fun setFmStructure(crossQuad: Boolean) {
        fmStructureCrossQuad = crossQuad
    }

    override fun setTotalFeedback(amount: Float) {
        val scaledAmount = amount * 20.0
        totalFbGain.inputB.set(scaledAmount)
    }

    override fun setVibrato(amount: Float) {
        val depthHz = amount * 20.0
        vibratoDepthGain.inputB.set(depthHz)
    }

    override fun setVoiceCoupling(amount: Float) {
        val depthHz = amount * 30.0
        voices.forEach { voice ->
            voice.couplingDepth.set(depthHz)
        }
    }

    override fun playTestTone(frequency: Float) {
        // Not implemented in shared engine
    }

    override fun stopTestTone() {
        // Not implemented in shared engine
    }

    override fun getPeak(): Float {
        return peakFollower.getCurrent().toFloat()
    }

    override fun getCpuLoad(): Float {
        return audioEngine.getCpuLoad()
    }
}
