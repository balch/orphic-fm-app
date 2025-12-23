package org.balch.songe.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSongeEngine : SongeEngine {
    override fun start() {
        // Stub
    }

    override fun stop() {
        // Stub
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        // Stub
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        // Stub
    }

    override fun setVoiceFeedback(index: Int, amount: Float) {
        // Stub
    }

    override fun setVoiceFmDepth(index: Int, amount: Float) {
        // Stub
    }

    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        // Stub
    }

    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        // Stub
    }

    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        // Stub
    }

    override fun setQuadHold(quadIndex: Int, amount: Float) {
        // Stub
    }

    override fun setDrive(amount: Float) {}
    override fun setDistortionMix(amount: Float) {}
    override fun setMasterVolume(amount: Float) {}

    override fun setDelay(time: Float, feedback: Float) {}

    override fun setDelayTime(index: Int, time: Float) {}
    override fun setDelayFeedback(amount: Float) {}
    override fun setDelayMix(amount: Float) {}
    override fun setDelayModDepth(index: Int, amount: Float) {}
    override fun setDelayModSource(index: Int, isLfo: Boolean) {}
    override fun setDelayLfoWaveform(isTriangle: Boolean) {}

    override fun setDuoModSource(duoIndex: Int, source: ModSource) {}
    override fun setFmStructure(crossQuad: Boolean) {}
    override fun setTotalFeedback(amount: Float) {}
    override fun setVibrato(amount: Float) {}
    override fun setVoiceCoupling(amount: Float) {}

    override fun setHyperLfoFreq(index: Int, frequency: Float) {}
    override fun setHyperLfoMode(mode: Int) {}
    override fun setHyperLfoLink(active: Boolean) {}

    override fun playTestTone(frequency: Float) {
        // TODO("Not yet implemented")
    }

    override fun stopTestTone() {
        // Stub
    }

    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f

    // Reactive monitoring flows (stub values)
    override val peakFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
    override val cpuLoadFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()

    // Getters for State Saving (Stubs)
    override fun getVoiceTune(index: Int): Float = 0f
    override fun getVoiceFmDepth(index: Int): Float = 0f
    override fun getVoiceEnvelopeSpeed(index: Int): Float = 0f
    override fun getPairSharpness(pairIndex: Int): Float = 0f
    override fun getDuoModSource(duoIndex: Int): ModSource = ModSource.OFF
    override fun getQuadPitch(quadIndex: Int): Float = 0.5f
    override fun getQuadHold(quadIndex: Int): Float = 0f
    override fun getFmStructureCrossQuad(): Boolean = false
    override fun getTotalFeedback(): Float = 0f
    override fun getVibrato(): Float = 0f
    override fun getVoiceCoupling(): Float = 0f
    override fun getDelayTime(index: Int): Float = 0f
    override fun getDelayFeedback(): Float = 0f
    override fun getDelayMix(): Float = 0f
    override fun getDelayModDepth(index: Int): Float = 0f
    override fun getDelayModSourceIsLfo(index: Int): Boolean = false
    override fun getDelayLfoWaveformIsTriangle(): Boolean = true
    override fun getHyperLfoFreq(index: Int): Float = 0f
    override fun getHyperLfoMode(): Int = 1 // OFF
    override fun getHyperLfoLink(): Boolean = false
    override fun getDrive(): Float = 0f
    override fun getDistortionMix(): Float = 0f
    override fun getMasterVolume(): Float = 0.5f
}
