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
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) { _quadVolume[quadIndex] = targetVolume }
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
    override fun getCurrentTime(): Double = org.balch.orpheus.util.currentTimeMillis() / 1000.0

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
    
    // Drum trigger
    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {}
    override fun triggerDrum(type: Int, accent: Float) {}
    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {}
    
    // Resonator (Rings) stub implementations
    private var _resonatorMode = 0
    private var _resonatorTarget = 1  // 0=Drums, 1=Both, 2=Synth
    private var _resonatorTargetMix = 0.5f  // Continuous mix
    private var _resonatorStructure = 0.25f
    private var _resonatorBrightness = 0.5f
    private var _resonatorDamping = 0.3f
    private var _resonatorPosition = 0.5f
    private var _resonatorMix = 0.5f
    
    override fun setResonatorMode(mode: Int) { _resonatorMode = mode }
    override fun setResonatorTarget(target: Int) { _resonatorTarget = target }
    override fun setResonatorTargetMix(targetMix: Float) { _resonatorTargetMix = targetMix }
    override fun setResonatorStructure(value: Float) { _resonatorStructure = value }
    override fun setResonatorBrightness(value: Float) { _resonatorBrightness = value }
    override fun setResonatorDamping(value: Float) { _resonatorDamping = value }
    override fun setResonatorPosition(value: Float) { _resonatorPosition = value }
    override fun setResonatorMix(value: Float) { _resonatorMix = value }
    override fun strumResonator(frequency: Float) {}
    
    override fun getResonatorMode(): Int = _resonatorMode
    override fun getResonatorTarget(): Int = _resonatorTarget
    override fun getResonatorTargetMix(): Float = _resonatorTargetMix
    override fun getResonatorStructure(): Float = _resonatorStructure
    override fun getResonatorBrightness(): Float = _resonatorBrightness
    override fun getResonatorDamping(): Float = _resonatorDamping
    override fun getResonatorPosition(): Float = _resonatorPosition
    override fun getResonatorMix(): Float = _resonatorMix
    
    // Stub implementations for new methods
    private var _resonatorSnapBack = false
    override fun getResonatorSnapBack(): Boolean = _resonatorSnapBack
    override fun setResonatorSnapBack(enabled: Boolean) { _resonatorSnapBack = enabled }
    
    // Drum stub params
    private val _drumFreq = FloatArray(3)
    private val _drumTone = FloatArray(3)
    private val _drumDecay = FloatArray(3)
    private val _drumP4 = FloatArray(3)
    private val _drumP5 = FloatArray(3)

    override fun getDrumFrequency(type: Int): Float = _drumFreq.getOrElse(type) { 0f }
    override fun getDrumTone(type: Int): Float = _drumTone.getOrElse(type) { 0f }
    override fun getDrumDecay(type: Int): Float = _drumDecay.getOrElse(type) { 0f }
    override fun getDrumP4(type: Int): Float = _drumP4.getOrElse(type) { 0f }
    override fun getDrumP5(type: Int): Float = _drumP5.getOrElse(type) { 0f }
    
    // Beat Sequencer Stubs
    private var _beatsX = 0.5f
    private var _beatsY = 0.5f
    private val _beatsDensities = FloatArray(3) { 0.5f }
    private var _beatsBpm = 120f
    private var _beatsMode = 0
    private val _beatsLengths = IntArray(3) { 16 }
    private var _beatsRandomness = 0f
    private var _beatsSwing = 0f
    
    override fun setBeatsX(x: Float) { _beatsX = x }
    override fun getBeatsX(): Float = _beatsX
    override fun setBeatsY(y: Float) { _beatsY = y }
    override fun getBeatsY(): Float = _beatsY
    override fun setBeatsDensity(index: Int, density: Float) { if (index in 0..2) _beatsDensities[index] = density }
    override fun getBeatsDensity(index: Int): Float = _beatsDensities.getOrElse(index) { 0.5f }
    override fun setBeatsBpm(bpm: Float) { _beatsBpm = bpm }
    override fun getBeatsBpm(): Float = _beatsBpm
    override fun setBeatsOutputMode(mode: Int) { _beatsMode = mode }
    override fun getBeatsOutputMode(): Int = _beatsMode
    override fun setBeatsEuclideanLength(index: Int, length: Int) { if (index in 0..2) _beatsLengths[index] = length }
    override fun getBeatsEuclideanLength(index: Int): Int = _beatsLengths.getOrElse(index) { 16 }
    override fun setBeatsRandomness(randomness: Float) { _beatsRandomness = randomness }
    override fun getBeatsRandomness(): Float = _beatsRandomness
    override fun setBeatsSwing(swing: Float) { _beatsSwing = swing }
    override fun getBeatsSwing(): Float = _beatsSwing
    
    private var _beatsMix = 0.7f
    override fun setBeatsMix(mix: Float) { _beatsMix = mix }
    override fun getBeatsMix(): Float = _beatsMix

    // Clouds Stubs
    private var _cloudsPosition = 0f
    private var _cloudsSize = 0f
    private var _cloudsPitch = 0f
    private var _cloudsDensity = 0f
    private var _cloudsTexture = 0f
    private var _cloudsDryWet = 0f
    private var _cloudsFreeze = false
    
    override fun setGrainsPosition(value: Float) { _cloudsPosition = value }
    override fun setGrainsSize(value: Float) { _cloudsSize = value }
    override fun setGrainsPitch(value: Float) { _cloudsPitch = value }
    override fun setGrainsDensity(value: Float) { _cloudsDensity = value }
    override fun setGrainsTexture(value: Float) { _cloudsTexture = value }
    override fun setGrainsDryWet(value: Float) { _cloudsDryWet = value }
    override fun setGrainsFreeze(frozen: Boolean) { _cloudsFreeze = frozen }
    override fun setGrainsTrigger(trigger: Boolean) {}

    override fun getGrainsPosition(): Float = _cloudsPosition
    override fun getGrainsSize(): Float = _cloudsSize
    override fun getGrainsPitch(): Float = _cloudsPitch
    override fun getGrainsDensity(): Float = _cloudsDensity
    override fun getGrainsTexture(): Float = _cloudsTexture
    override fun getGrainsDryWet(): Float = _cloudsDryWet
    override fun getGrainsFreeze(): Boolean = _cloudsFreeze
    
    private var _cloudsMode = 0
    override fun setGrainsMode(mode: Int) { _cloudsMode = mode }
    override fun getGrainsMode(): Int = _cloudsMode
    
    // Looper stubs
    override fun setLooperRecord(recording: Boolean) {}
    override fun setLooperPlay(playing: Boolean) {}
    override fun setLooperOverdub(overdub: Boolean) {}
    override fun clearLooper() {}
    override fun getLooperPosition(): Float = 0f
    override fun getLooperDuration(): Double = 0.0
    
    // Warps stubs
    private var _warpsAlgorithm = 0f
    private var _warpsTimbre = 0.5f
    private var _warpsLevel1 = 0.5f
    private var _warpsLevel2 = 0.5f
    private var _warpsCarrierSource = 0
    private var _warpsModulatorSource = 1
    private var _warpsMix = 0.5f
    
    override fun setWarpsAlgorithm(value: Float) { _warpsAlgorithm = value }
    override fun setWarpsTimbre(value: Float) { _warpsTimbre = value }
    override fun setWarpsLevel1(value: Float) { _warpsLevel1 = value }
    override fun setWarpsLevel2(value: Float) { _warpsLevel2 = value }
    override fun setWarpsCarrierSource(source: Int) { _warpsCarrierSource = source }
    override fun setWarpsModulatorSource(source: Int) { _warpsModulatorSource = source }
    override fun setWarpsMix(value: Float) { _warpsMix = value }
    
    override fun getWarpsAlgorithm(): Float = _warpsAlgorithm
    override fun getWarpsTimbre(): Float = _warpsTimbre
    override fun getWarpsLevel1(): Float = _warpsLevel1
    override fun getWarpsLevel2(): Float = _warpsLevel2
    override fun getWarpsCarrierSource(): Int = _warpsCarrierSource
    override fun getWarpsModulatorSource(): Int = _warpsModulatorSource
    override fun getWarpsMix(): Float = _warpsMix
}

