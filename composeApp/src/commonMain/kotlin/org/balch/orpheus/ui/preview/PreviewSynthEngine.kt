package org.balch.orpheus.ui.preview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine

class PreviewSynthEngine() : SynthEngine {
    override fun start() {
    }

    override fun stop() {
    }

    override fun setVoiceTune(index: Int, tune: Float) {
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
    }

    override fun setVoiceFeedback(index: Int, amount: Float) {}
    override fun setVoiceFmDepth(index: Int, amount: Float) {}
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {}
    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {}

    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
    }

    override fun setQuadHold(quadIndex: Int, amount: Float) {
    }

    override fun setVoiceHold(index: Int, amount: Float) {
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

    override fun playTestTone(frequency: Float) {}
    override fun stopTestTone() {}

    override fun getPeak(): Float = 0.5f
    override fun getCpuLoad(): Float = 12.5f

    // Reactive monitoring flows (static preview values)
    override val peakFlow: StateFlow<Float> = MutableStateFlow(0.5f).asStateFlow()
    override val cpuLoadFlow: StateFlow<Float> = MutableStateFlow(12.5f).asStateFlow()

    // Visualization flows (static preview values for plasma background)
    override val voiceLevelsFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(8) { 0.3f }).asStateFlow()
    override val lfoOutputFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
    override val masterLevelFlow: StateFlow<Float> = MutableStateFlow(0.4f).asStateFlow()

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

    // Stereo
    override fun setVoicePan(index: Int, pan: Float) {}
    override fun getVoicePan(index: Int): Float = 0f
    override fun setMasterPan(pan: Float) {}
    override fun getMasterPan(): Float = 0f
    override fun setStereoMode(mode: StereoMode) {}
    override fun getStereoMode(): StereoMode = StereoMode.VOICE_PAN
}