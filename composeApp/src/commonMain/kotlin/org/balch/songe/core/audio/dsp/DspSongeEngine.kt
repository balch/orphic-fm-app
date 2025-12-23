package org.balch.songe.core.audio.dsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.songe.core.audio.ModSource
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.audio.StereoMode
import org.balch.songe.util.Logger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Shared implementation of SongeEngine using DSP primitive interfaces.
 * All audio routing logic is platform-independent.
 */
class DspSongeEngine(private val audioEngine: AudioEngine) : SongeEngine {

    // 8 Voices with pitch ranges (0.5=bass, 1.0=mid, 2.0=high)
    private val voices = listOf(
        // Pair 1-2: Bass range
        DspVoice(audioEngine, pitchMultiplier = 0.5),
        DspVoice(audioEngine, pitchMultiplier = 0.5),
        // Pair 3-4: Mid range
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        // Pair 5-6: Mid range
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        // Pair 7-8: High range (octave up)
        DspVoice(audioEngine, pitchMultiplier = 2.0),
        DspVoice(audioEngine, pitchMultiplier = 2.0)
    )

    // Dual Delay & Modulation
    private val delay1 = audioEngine.createDelayLine()
    private val delay2 = audioEngine.createDelayLine()
    private val delay1FeedbackGain = audioEngine.createMultiply()
    private val delay2FeedbackGain = audioEngine.createMultiply()

    // Delay Modulation
    private val hyperLfo = DspHyperLfo(audioEngine)

    // Convert bipolar LFO (-1 to +1) to unipolar (0 to 1) to prevent negative delay times
    // Formula: unipolar = (bipolar * 0.5) + 0.5
    private val lfoToUnipolar1 = audioEngine.createMultiplyAdd()
    private val lfoToUnipolar2 = audioEngine.createMultiplyAdd()
    private val delay1ModMixer = audioEngine.createMultiplyAdd() // (UnipolarLFO * Depth) + BaseTime
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
    
    // Stereo Output Bus
    private val masterGainLeft = audioEngine.createMultiply()
    private val masterGainRight = audioEngine.createMultiply()
    private val stereoSumLeft = audioEngine.createPassThrough()  // Sum all L channels
    private val stereoSumRight = audioEngine.createPassThrough() // Sum all R channels
    private val masterPanLeft = audioEngine.createMultiply()
    private val masterPanRight = audioEngine.createMultiply()
    
    // Per-voice stereo panning (equal-power pan law)
    private val voicePanLeft = List(8) { audioEngine.createMultiply() }
    private val voicePanRight = List(8) { audioEngine.createMultiply() }

    // Parallel Clean/Distorted Paths (Lyra MIX)
    private val preDistortionSummer = audioEngine.createAdd()
    private val cleanPathGain = audioEngine.createMultiply()
    private val distortedPathGain = audioEngine.createMultiply()
    private val postMixSummer = audioEngine.createAdd()

    // Dry/Wet Mix for Delays
    private val dryGain = audioEngine.createMultiply()
    private val wetGain = audioEngine.createMultiply()
    
    // Stereo Delays: Per-delay L/R wet gains for stereo routing
    private val delay1WetLeft = audioEngine.createMultiply()
    private val delay1WetRight = audioEngine.createMultiply()
    private val delay2WetLeft = audioEngine.createMultiply()
    private val delay2WetRight = audioEngine.createMultiply()

    // Vibrato (Global pitch wobble)
    private val vibratoLfo = audioEngine.createSineOscillator()
    private val vibratoDepthGain = audioEngine.createMultiply()

    // State caches (Optimized specifically for dsp calculation reuse if any)
    private val quadPitchOffsets = DoubleArray(2) { 0.5 }
    // voiceTuneCache merged into _voiceTune below, keeping distinct for now to avoid breaking existing internal logic immediately, but ideally should merge.
    // Existing logic uses voiceTuneCache[index] as Double. _voiceTune is Float.
    private val voiceTuneCache = DoubleArray(8) { 0.5 }
    // fmStructureCrossQuad removed in favor of _fmStructureCrossQuad

    // Reactive monitoring flows
    private val _peakFlow = MutableStateFlow(0f)
    override val peakFlow: StateFlow<Float> = _peakFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(0f)
    override val cpuLoadFlow: StateFlow<Float> = _cpuLoadFlow.asStateFlow()

    // State Caches (Backing fields for getters)
    // Voices
    private val _voiceTune = FloatArray(8) { 0.5f } // Default from VoiceUiState
    private val _voiceFmDepth = FloatArray(8) { 0.0f }
    private val _voiceEnvelopeSpeed = FloatArray(8) { 0.0f }
    private val _pairSharpness = FloatArray(4) { 0.0f }
    private val _duoModSource = Array(4) { ModSource.OFF }
    private val _quadPitch = FloatArray(2) { 0.5f }
    private val _quadHold = FloatArray(2) { 0.0f }
    private var _fmStructureCrossQuad = false
    private var _totalFeedback = 0.0f
    private var _vibrato = 0.0f
    private var _voiceCoupling = 0.0f

    // Delay
    private val _delayTime = FloatArray(2) { 0.3f }
    private var _delayFeedback = 0.5f
    private var _delayMix = 0.5f
    private val _delayModDepth = FloatArray(2) { 0.0f }
    private val _delayModSourceIsLfo = BooleanArray(2) { true }
    private var _delayLfoWaveformIsTriangle = true

    // LFO
    private val _hyperLfoFreq = FloatArray(2) { 0.0f }
    private var _hyperLfoMode = 1 // OFF (from HyperLfoMode.OFF.ordinal which is typically 1 if OFF is middle, checking enum... actually OFF is usually 0 or 1 depending on list. In UI State default is OFF. Logic below will confirm)
    private var _hyperLfoLink = false

    // Distortion
    private var _drive = 0.0f
    private var _distortionMix = 0.5f
    private var _masterVolume = 0.7f

    // Stereo
    private val _voicePan = FloatArray(8) { 0f }  // -1=L, 0=Center, 1=R
    private var _masterPan = 0f
    private var _stereoMode = StereoMode.VOICE_PAN
    
    // Default voice pan positions (bass center, mids slight L/R, highs wide)
    private val defaultVoicePans = floatArrayOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f)

    // Monitoring coroutine scope
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Add all units to audio engine
        audioEngine.addUnit(delay1)
        audioEngine.addUnit(delay2)
        audioEngine.addUnit(delay1FeedbackGain)
        audioEngine.addUnit(delay2FeedbackGain)
        audioEngine.addUnit(lfoToUnipolar1)
        audioEngine.addUnit(lfoToUnipolar2)
        audioEngine.addUnit(delay1ModMixer)
        audioEngine.addUnit(delay2ModMixer)
        audioEngine.addUnit(selfMod1Attenuator)
        audioEngine.addUnit(selfMod2Attenuator)
        audioEngine.addUnit(totalFbGain)
        audioEngine.addUnit(peakFollower)
        audioEngine.addUnit(limiter)
        audioEngine.addUnit(driveGain)
        audioEngine.addUnit(masterGainLeft)
        audioEngine.addUnit(masterGainRight)
        audioEngine.addUnit(stereoSumLeft)
        audioEngine.addUnit(stereoSumRight)
        audioEngine.addUnit(masterPanLeft)
        audioEngine.addUnit(masterPanRight)
        voicePanLeft.forEach { audioEngine.addUnit(it) }
        voicePanRight.forEach { audioEngine.addUnit(it) }
        audioEngine.addUnit(preDistortionSummer)
        audioEngine.addUnit(cleanPathGain)
        audioEngine.addUnit(distortedPathGain)
        audioEngine.addUnit(postMixSummer)
        audioEngine.addUnit(dryGain)
        audioEngine.addUnit(wetGain)
        audioEngine.addUnit(delay1WetLeft)
        audioEngine.addUnit(delay1WetRight)
        audioEngine.addUnit(delay2WetLeft)
        audioEngine.addUnit(delay2WetRight)
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
        masterGainLeft.inputB.set(0.7)
        masterGainRight.inputB.set(0.7)
        masterPanLeft.inputB.set(1.0)  // Default: center (equal L/R)
        masterPanRight.inputB.set(1.0)

        // Clean/Distorted Mix defaults (50/50)
        cleanPathGain.inputB.set(0.5)
        distortedPathGain.inputB.set(0.5)

        // Dry/Wet defaults (50/50 mix)
        dryGain.inputB.set(0.5)
        wetGain.inputB.set(0.5)
        
        // Stereo delay wet gains default (VOICE_PAN mode: both delays to both channels)
        delay1WetLeft.inputB.set(1.0)
        delay1WetRight.inputB.set(1.0)
        delay2WetLeft.inputB.set(1.0)
        delay2WetRight.inputB.set(1.0)

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
        // Convert bipolar LFO (-1 to +1) to unipolar (0 to 1): u = (x * 0.5) + 0.5
        hyperLfo.output.connect(lfoToUnipolar1.inputA)
        lfoToUnipolar1.inputB.set(0.5)
        lfoToUnipolar1.inputC.set(0.5)

        hyperLfo.output.connect(lfoToUnipolar2.inputA)
        lfoToUnipolar2.inputB.set(0.5)
        lfoToUnipolar2.inputC.set(0.5)

        // Connect unipolar LFO to modulation mixers
        lfoToUnipolar1.output.connect(delay1ModMixer.inputA)
        lfoToUnipolar2.output.connect(delay2ModMixer.inputA)
        delay1ModMixer.inputB.set(0.0) // Mod depth
        delay2ModMixer.inputB.set(0.0)
        delay1ModMixer.output.connect(delay1.delay)
        delay2ModMixer.output.connect(delay2.delay)

        // ROUTING
        // Delays -> WetGain -> PreDistortionSummer (for mono mix path)
        delay1.output.connect(wetGain.inputA)
        delay2.output.connect(wetGain.inputA)
        wetGain.output.connect(preDistortionSummer.inputA)
        
        // Delays -> Stereo Wet Gains -> Stereo Sum (for stereo delay mode)
        delay1.output.connect(delay1WetLeft.inputA)
        delay1.output.connect(delay1WetRight.inputA)
        delay2.output.connect(delay2WetLeft.inputA)
        delay2.output.connect(delay2WetRight.inputA)
        delay1WetLeft.output.connect(stereoSumLeft.input)
        delay1WetRight.output.connect(stereoSumRight.input)
        delay2WetLeft.output.connect(stereoSumLeft.input)
        delay2WetRight.output.connect(stereoSumRight.input)

        // DryGain -> PreDistortionSummer
        dryGain.output.connect(preDistortionSummer.inputB)

        // Split into Clean and Distorted paths
        preDistortionSummer.output.connect(cleanPathGain.inputA)
        cleanPathGain.output.connect(postMixSummer.inputA)

        preDistortionSummer.output.connect(driveGain.inputA)
        driveGain.output.connect(limiter.input)
        limiter.output.connect(distortedPathGain.inputA)
        distortedPathGain.output.connect(postMixSummer.inputB)

        // PostMixSummer -> Stereo Master Path
        // postMixSummer feeds both stereo channels (panned at voice level)
        postMixSummer.output.connect(stereoSumLeft.input)
        postMixSummer.output.connect(stereoSumRight.input)
        
        // Stereo Sum -> Master Pan -> Master Gain -> LineOut
        stereoSumLeft.output.connect(masterPanLeft.inputA)
        stereoSumRight.output.connect(masterPanRight.inputA)
        masterPanLeft.output.connect(masterGainLeft.inputA)
        masterPanRight.output.connect(masterGainRight.inputA)
        masterGainLeft.output.connect(audioEngine.lineOutLeft)
        masterGainRight.output.connect(audioEngine.lineOutRight)
        
        // Peak follower monitors left channel (representative of output)
        masterGainLeft.output.connect(peakFollower.input)

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
        
        // Wire per-voice pan gains to stereo bus
        // Voice -> PanL/PanR -> StereoSumL/StereoSumR  
        voices.forEachIndexed { index, voice ->
            voice.output.connect(voicePanLeft[index].inputA)
            voice.output.connect(voicePanRight[index].inputA)
            voicePanLeft[index].output.connect(stereoSumLeft.input)
            voicePanRight[index].output.connect(stereoSumRight.input)
        }
        
        // Apply default voice pan positions
        defaultVoicePans.forEachIndexed { index, pan ->
            setVoicePan(index, pan)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SongeEngine Implementation
    // ═══════════════════════════════════════════════════════════

    private var monitoringJob: Job? = null

    override fun start() {
        if (audioEngine.isRunning) return
        Logger.info { "Starting Shared Audio Engine..." }
        audioEngine.start()

        // Start monitoring flow updates
        monitoringJob = monitoringScope.launch {
            while (isActive) {
                _peakFlow.value = peakFollower.getCurrent().toFloat()
                _cpuLoadFlow.value = audioEngine.getCpuLoad()
                delay(100)
            }
        }
        Logger.info { "Audio Engine Started" }
    }

    override fun stop() {
        Logger.info { "Stopping Audio Engine..." }
        monitoringJob?.cancel()
        monitoringJob = null
        audioEngine.stop()
        Logger.info { "Audio Engine Stopped" }
    }

    override fun setDrive(amount: Float) {
        _drive = amount
        val driveVal = 1.0 + (amount * 14.0) // Reduced from 49 for warmer saturation
        limiter.drive.set(driveVal)
    }

    override fun setDistortionMix(amount: Float) {
        _distortionMix = amount
        val distortedLevel = amount
        val cleanLevel = 1.0f - amount
        cleanPathGain.inputB.set(cleanLevel.toDouble())
        distortedPathGain.inputB.set(distortedLevel.toDouble())
    }

    override fun setMasterVolume(amount: Float) {
        _masterVolume = amount
        masterGainLeft.inputB.set(amount.toDouble())
        masterGainRight.inputB.set(amount.toDouble())
    }

    override fun setDelayTime(index: Int, time: Float) {
        _delayTime[index] = time
        val delaySeconds = 0.01 + (time * 1.99)
        if (index == 0) {
            delay1ModMixer.inputC.set(delaySeconds)
        } else {
            delay2ModMixer.inputC.set(delaySeconds)
        }
    }

    override fun setDelayFeedback(amount: Float) {
        _delayFeedback = amount
        val fb = amount * 0.95
        delay1FeedbackGain.inputB.set(fb)
        delay2FeedbackGain.inputB.set(fb)
    }

    override fun setDelayMix(amount: Float) {
        _delayMix = amount
        val wetLevel = amount
        val dryLevel = 1.0f - amount
        dryGain.inputB.set(dryLevel.toDouble())
        wetGain.inputB.set(wetLevel.toDouble())
    }

    override fun setDelayModDepth(index: Int, amount: Float) {
        _delayModDepth[index] = amount
        // Modulation depth: max 0.5 seconds
        // Since LFO is unipolar (0-1), delay time = baseTime + (unipolarLFO * depth)
        // This ensures delay times are always >= baseTime (no negative values)
        val depth = amount * 0.5
        if (index == 0) {
            delay1ModMixer.inputB.set(depth)
        } else {
            delay2ModMixer.inputB.set(depth)
        }
    }

    override fun setDelayModSource(index: Int, isLfo: Boolean) {
        _delayModSourceIsLfo[index] = isLfo
        val targetConverter = if (index == 0) lfoToUnipolar1 else lfoToUnipolar2
        val targetMixer = if (index == 0) delay1ModMixer else delay2ModMixer

        // Disconnect the converter's input
        targetConverter.inputA.disconnectAll()

        if (isLfo) {
            // Connect LFO -> Unipolar Converter -> Mixer (already wired at init)
            hyperLfo.output.connect(targetConverter.inputA)
        } else {
            // Connect Self-Modulation -> Unipolar Converter
            // Self-mod is already bipolar audio signal, so needs conversion too
            val attenuatedSelf =
                if (index == 0) selfMod1Attenuator.output else selfMod2Attenuator.output
            attenuatedSelf.connect(targetConverter.inputA)
        }
    }

    override fun setDelayLfoWaveform(isTriangle: Boolean) {
        _delayLfoWaveformIsTriangle = isTriangle
        hyperLfo.setTriangleMode(isTriangle)
    }

    @Deprecated("Use granular setDelayTime/Feedback instead")
    override fun setDelay(time: Float, feedback: Float) {
        setDelayTime(0, time)
        setDelayTime(1, time)
        setDelayFeedback(feedback)
    }

    override fun setHyperLfoFreq(index: Int, frequency: Float) {
        _hyperLfoFreq[index] = frequency
        val freqHz = 0.01 + (frequency * 10.0)
        if (index == 0) {
            hyperLfo.frequencyA.set(freqHz)
        } else {
            hyperLfo.frequencyB.set(freqHz)
        }
    }

    override fun setHyperLfoMode(mode: Int) {
        _hyperLfoMode = mode
        hyperLfo.setMode(mode)
    }

    override fun setHyperLfoLink(active: Boolean) {
        _hyperLfoLink = active
        hyperLfo.setLink(active)
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        _voiceTune[index] = tune
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
        _voiceFmDepth[index] = amount
        voices[index].fmDepth.set(amount.toDouble())
    }

    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        _voiceEnvelopeSpeed[index] = speed
        voices[index].setEnvelopeSpeed(speed)
    }

    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        _pairSharpness[pairIndex] = sharpness
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1
        voices[voiceA].sharpness.set(sharpness.toDouble())
        voices[voiceB].sharpness.set(sharpness.toDouble())
    }

    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        _quadPitch[quadIndex] = pitch
        quadPitchOffsets[quadIndex] = pitch.toDouble()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            updateVoiceFrequency(i)
        }
    }

    override fun setQuadHold(quadIndex: Int, amount: Float) {
        _quadHold[quadIndex] = amount
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setHoldLevel(amount.toDouble())
        }
    }

    override fun setDuoModSource(duoIndex: Int, source: ModSource) {
        _duoModSource[duoIndex] = source
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1

        voices[voiceA].modInput.disconnectAll()
        voices[voiceB].modInput.disconnectAll()

        when (source) {
            ModSource.OFF -> { /* Already disconnected */
            }

            ModSource.LFO -> {
                hyperLfo.output.connect(voices[voiceA].modInput)
                hyperLfo.output.connect(voices[voiceB].modInput)
            }

            ModSource.VOICE_FM -> {
                if (_fmStructureCrossQuad) {
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
        _fmStructureCrossQuad = crossQuad
    }

    override fun setTotalFeedback(amount: Float) {
        _totalFeedback = amount
        val scaledAmount = amount * 20.0
        totalFbGain.inputB.set(scaledAmount)
    }

    override fun setVibrato(amount: Float) {
        _vibrato = amount
        val depthHz = amount * 20.0
        vibratoDepthGain.inputB.set(depthHz)
    }

    override fun setVoiceCoupling(amount: Float) {
        _voiceCoupling = amount
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

    // ═══════════════════════════════════════════════════════════
    // Getters for State Saving
    // ═══════════════════════════════════════════════════════════

    override fun getVoiceTune(index: Int): Float = _voiceTune[index]
    override fun getVoiceFmDepth(index: Int): Float = _voiceFmDepth[index]
    override fun getVoiceEnvelopeSpeed(index: Int): Float = _voiceEnvelopeSpeed[index]
    override fun getPairSharpness(pairIndex: Int): Float = _pairSharpness[pairIndex]
    override fun getDuoModSource(duoIndex: Int): ModSource = _duoModSource[duoIndex]
    override fun getQuadPitch(quadIndex: Int): Float = _quadPitch[quadIndex]
    override fun getQuadHold(quadIndex: Int): Float = _quadHold[quadIndex]
    override fun getFmStructureCrossQuad(): Boolean = _fmStructureCrossQuad
    override fun getTotalFeedback(): Float = _totalFeedback
    override fun getVibrato(): Float = _vibrato
    override fun getVoiceCoupling(): Float = _voiceCoupling

    override fun getDelayTime(index: Int): Float = _delayTime[index]
    override fun getDelayFeedback(): Float = _delayFeedback
    override fun getDelayMix(): Float = _delayMix
    override fun getDelayModDepth(index: Int): Float = _delayModDepth[index]
    override fun getDelayModSourceIsLfo(index: Int): Boolean = _delayModSourceIsLfo[index]
    override fun getDelayLfoWaveformIsTriangle(): Boolean = _delayLfoWaveformIsTriangle

    override fun getHyperLfoFreq(index: Int): Float = _hyperLfoFreq[index]
    override fun getHyperLfoMode(): Int = _hyperLfoMode
    override fun getHyperLfoLink(): Boolean = _hyperLfoLink

    override fun getDrive(): Float = _drive
    override fun getDistortionMix(): Float = _distortionMix
    override fun getMasterVolume(): Float = _masterVolume

    // ═══════════════════════════════════════════════════════════
    // Stereo Control
    // ═══════════════════════════════════════════════════════════

    override fun setVoicePan(index: Int, pan: Float) {
        _voicePan[index] = pan.coerceIn(-1f, 1f)
        // Equal-power pan law: L = cos(angle), R = sin(angle)
        // angle: 0 = hard left (L=1, R=0), π/2 = hard right (L=0, R=1)
        // Map pan (-1 to +1) to angle (0 to π/2)
        val angle = ((pan + 1f) / 2f) * (PI / 2).toFloat()
        val leftGain = cos(angle.toDouble())
        val rightGain = sin(angle.toDouble())
        voicePanLeft[index].inputB.set(leftGain)
        voicePanRight[index].inputB.set(rightGain)
    }

    override fun getVoicePan(index: Int): Float = _voicePan[index]

    override fun setMasterPan(pan: Float) {
        _masterPan = pan.coerceIn(-1f, 1f)
        // Equal-power pan for master
        val angle = ((pan + 1f) / 2f) * (PI / 2).toFloat()
        val leftGain = cos(angle.toDouble())
        val rightGain = sin(angle.toDouble())
        masterPanLeft.inputB.set(leftGain)
        masterPanRight.inputB.set(rightGain)
    }

    override fun getMasterPan(): Float = _masterPan

    override fun setStereoMode(mode: StereoMode) {
        _stereoMode = mode
        when (mode) {
            StereoMode.VOICE_PAN -> {
                // Both delays go to both channels equally (mono wet mix)
                delay1WetLeft.inputB.set(1.0)
                delay1WetRight.inputB.set(1.0)
                delay2WetLeft.inputB.set(1.0)
                delay2WetRight.inputB.set(1.0)
            }
            StereoMode.STEREO_DELAYS -> {
                // Delay 1 -> Left only, Delay 2 -> Right only
                delay1WetLeft.inputB.set(1.0)
                delay1WetRight.inputB.set(0.0)
                delay2WetLeft.inputB.set(0.0)
                delay2WetRight.inputB.set(1.0)
            }
        }
    }

    override fun getStereoMode(): StereoMode = _stereoMode
}
