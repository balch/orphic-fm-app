package org.balch.orpheus.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test fake implementation of SynthEngine for unit testing.
 * 
 * All methods are stubs that track values for verification.
 * Override specific properties/methods as needed in tests.
 */
open class TestSynthEngine : SynthEngine {
    // Mutable state for testing
    var _masterVolume: Float = 0.7f
    var _drive: Float = 0f
    var _distortionMix: Float = 0.5f
    val _voiceTunes = FloatArray(12) { 0.5f }
    val _voiceFmDepths = FloatArray(12) { 0f }
    val _voiceEnvelopeSpeeds = FloatArray(12) { 0f }
    val _pairSharpness = FloatArray(6) { 0f }
    val _duoModSources = Array(6) { ModSource.OFF }
    val _quadPitch = FloatArray(3) { 0.5f }
    val _quadHold = FloatArray(3) { 0f }
    val _quadVolume = FloatArray(3) { 1f }
    var _fmStructureCrossQuad = false
    var _totalFeedback = 0f
    var _vibrato = 0f
    var _voiceCoupling = 0f
    val _delayTime = FloatArray(2) { 0.3f }
    var _delayFeedback = 0.5f
    var _delayMix = 0.5f
    val _delayModDepth = FloatArray(2) { 0f }
    val _delayModSourceIsLfo = BooleanArray(2) { true }
    var _delayLfoWaveformIsTriangle = true
    val _hyperLfoFreq = FloatArray(2) { 0f }
    var _hyperLfoMode = 1 // OFF
    var _hyperLfoLink = false
    val _voicePan = FloatArray(12) { 0f }
    var _masterPan = 0f
    var _stereoMode = StereoMode.VOICE_PAN

    // Mutable flows for reactive testing
    private val _peakFlow = MutableStateFlow(0f)
    private val _cpuLoadFlow = MutableStateFlow(0f)
    private val _voiceLevelsFlow = MutableStateFlow(FloatArray(12))
    private val _lfoOutputFlow = MutableStateFlow(0f)
    private val _masterLevelFlow = MutableStateFlow(0f)

    // Lifecycle
    override fun start() {}
    override fun stop() {}

    // Getters
    override fun getMasterVolume(): Float = _masterVolume
    override fun getDrive(): Float = _drive
    override fun getDistortionMix(): Float = _distortionMix
    override fun getVoiceTune(index: Int): Float = _voiceTunes[index]
    override fun getVoiceFmDepth(index: Int): Float = _voiceFmDepths[index]
    override fun getVoiceEnvelopeSpeed(index: Int): Float = _voiceEnvelopeSpeeds[index]
    override fun getPairSharpness(pairIndex: Int): Float = _pairSharpness[pairIndex]
    override fun getDuoModSource(duoIndex: Int): ModSource = _duoModSources[duoIndex]
    override fun getQuadPitch(quadIndex: Int): Float = _quadPitch[quadIndex]
    override fun getQuadHold(quadIndex: Int): Float = _quadHold[quadIndex]
    override fun getQuadVolume(quadIndex: Int): Float = _quadVolume[quadIndex]
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
    override fun getVoicePan(index: Int): Float = _voicePan[index]
    override fun getMasterPan(): Float = _masterPan
    override fun getStereoMode(): StereoMode = _stereoMode

    // Setters
    override fun setMasterVolume(value: Float) { _masterVolume = value }
    override fun setDrive(value: Float) { _drive = value }
    override fun setDistortionMix(value: Float) { _distortionMix = value }
    override fun setVoiceTune(index: Int, tune: Float) { _voiceTunes[index] = tune }
    override fun setVoiceGate(index: Int, active: Boolean) {}
    override fun setVoiceFeedback(index: Int, amount: Float) {}
    override fun setVoiceFmDepth(index: Int, amount: Float) { _voiceFmDepths[index] = amount }
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) { _voiceEnvelopeSpeeds[index] = speed }
    override fun setPairSharpness(pairIndex: Int, sharpness: Float) { _pairSharpness[pairIndex] = sharpness }
    override fun setDuoModSource(duoIndex: Int, source: ModSource) { _duoModSources[duoIndex] = source }
    override fun setQuadPitch(quadIndex: Int, pitch: Float) { _quadPitch[quadIndex] = pitch }
    override fun setQuadHold(quadIndex: Int, amount: Float) { _quadHold[quadIndex] = amount }
    override fun setQuadVolume(quadIndex: Int, volume: Float) { _quadVolume[quadIndex] = volume }
    override fun setVoiceHold(index: Int, amount: Float) {}
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) {}
    override fun setFmStructure(crossQuad: Boolean) { _fmStructureCrossQuad = crossQuad }
    override fun setTotalFeedback(amount: Float) { _totalFeedback = amount }
    override fun setVibrato(amount: Float) { _vibrato = amount }
    override fun setVoiceCoupling(amount: Float) { _voiceCoupling = amount }
    override fun setDelayTime(index: Int, time: Float) { _delayTime[index] = time }
    override fun setDelayFeedback(amount: Float) { _delayFeedback = amount }
    override fun setDelayMix(amount: Float) { _delayMix = amount }
    override fun setDelayModDepth(index: Int, amount: Float) { _delayModDepth[index] = amount }
    override fun setDelayModSource(index: Int, isLfo: Boolean) { _delayModSourceIsLfo[index] = isLfo }
    override fun setDelayLfoWaveform(isTriangle: Boolean) { _delayLfoWaveformIsTriangle = isTriangle }
    override fun setDelay(time: Float, feedback: Float) {
        _delayTime[0] = time
        _delayTime[1] = time
        _delayFeedback = feedback
    }
    override fun setHyperLfoFreq(index: Int, frequency: Float) { _hyperLfoFreq[index] = frequency }
    override fun setHyperLfoMode(mode: Int) { _hyperLfoMode = mode }
    override fun setHyperLfoLink(active: Boolean) { _hyperLfoLink = active }
    override fun setVoicePan(index: Int, pan: Float) { _voicePan[index] = pan }
    override fun setMasterPan(pan: Float) { _masterPan = pan }
    override fun setStereoMode(mode: StereoMode) { _stereoMode = mode }

    // Automation (Test hooks)
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) {}
    override fun clearParameterAutomation(controlId: String) {}

    // Test tone
    override fun playTestTone(frequency: Float) {}
    override fun stopTestTone() {}

    // Monitoring
    override fun getPeak(): Float = _peakFlow.value
    override fun getCpuLoad(): Float = _cpuLoadFlow.value

    // Flows - override these in tests to inject values
    override val peakFlow: StateFlow<Float> = _peakFlow.asStateFlow()
    override val cpuLoadFlow: StateFlow<Float> = _cpuLoadFlow.asStateFlow()
    override val voiceLevelsFlow: StateFlow<FloatArray> = _voiceLevelsFlow.asStateFlow()
    override val lfoOutputFlow: StateFlow<Float> = _lfoOutputFlow.asStateFlow()
    override val masterLevelFlow: StateFlow<Float> = _masterLevelFlow.asStateFlow()
    
    // Additional required flows
    override val driveFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
    override val distortionMixFlow: StateFlow<Float> = MutableStateFlow(0.5f).asStateFlow()
    override val delayMixFlow: StateFlow<Float> = MutableStateFlow(0.5f).asStateFlow()
    override val delayFeedbackFlow: StateFlow<Float> = MutableStateFlow(0.5f).asStateFlow()
    override val quadPitchFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(3) { 0.5f }).asStateFlow()
    override val quadHoldFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(3)).asStateFlow()

    // Test helpers to emit flow values
    fun emitPeak(value: Float) { _peakFlow.value = value }
    fun emitCpuLoad(value: Float) { _cpuLoadFlow.value = value }
    fun emitVoiceLevels(levels: FloatArray) { _voiceLevelsFlow.value = levels }
    fun emitLfoOutput(value: Float) { _lfoOutputFlow.value = value }
    fun emitMasterLevel(value: Float) { _masterLevelFlow.value = value }
    
    // Bender methods
    private var _bend: Float = 0f
    private val _bendFlow = MutableStateFlow(0f)
    override fun setBend(amount: Float) { _bend = amount; _bendFlow.value = amount }
    override fun getBend(): Float = _bend
    override val bendFlow: StateFlow<Float> = _bendFlow.asStateFlow()
    
    // String bend methods
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {}
    override fun releaseStringBend(stringIndex: Int): Int = 250
    override fun resetStringBenders() {}
    
    // Slide bar methods
    override fun setSlideBar(yPosition: Float, xPosition: Float) {}
    override fun releaseSlideBar() {}
}
