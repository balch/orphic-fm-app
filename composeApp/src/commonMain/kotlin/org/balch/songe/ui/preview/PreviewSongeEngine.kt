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

    override fun setVoiceFeedback(index: Int, amount: Float) {
    }

    override fun setGroupPitch(groupIndex: Int, pitch: Float) {
    }

    override fun setGroupFm(groupIndex: Int, amount: Float) {
    }

    override fun setDrive(amount: Float) {}
    override fun setMasterVolume(amount: Float) {}
    override fun setDelay(time: Float, feedback: Float) {}
    
    override fun setDelayTime(index: Int, time: Float) {}
    override fun setDelayFeedback(amount: Float) {}
    override fun setDelayMix(amount: Float) {}
    override fun setDelayModDepth(index: Int, amount: Float) {}
    override fun setDelayModSource(index: Int, isLfo: Boolean) {}

    override fun setHyperLfoFreq(index: Int, frequency: Float) {}
    override fun setHyperLfoMode(andMode: Boolean) {}
    override fun setHyperLfoLink(active: Boolean) {}

    override fun playTestTone(frequency: Float) {}
    override fun stopTestTone() {}
    
    override fun getPeak(): Float = 0.5f
    override fun getCpuLoad(): Float = 12.5f
}