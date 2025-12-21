package org.balch.songe.ui.preview

import org.balch.songe.audio.SongeEngine

class PreviewSongeEngine(): SongeEngine {
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

    override fun setDuoModSource(duoIndex: Int, source: org.balch.songe.audio.ModSource) {}
    override fun setFmStructure(crossQuad: Boolean) {}
    override fun setTotalFeedback(amount: Float) {}
    override fun setVibrato(amount: Float) {}
    override fun setVoiceCoupling(amount: Float) {}
    
    override fun setHyperLfoFreq(index: Int, frequency: Float) {}
    override fun setHyperLfoMode(andMode: Boolean) {}
    override fun setHyperLfoLink(active: Boolean) {}

    override fun playTestTone(frequency: Float) {}
    override fun stopTestTone() {}
    
    override fun getPeak(): Float = 0.5f
    override fun getCpuLoad(): Float = 12.5f
}