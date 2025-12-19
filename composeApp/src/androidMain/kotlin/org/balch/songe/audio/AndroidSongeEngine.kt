package org.balch.songe.audio

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

    override fun setVoiceEnvelopeMode(index: Int, isFast: Boolean) {
         // Stub
    }

    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
         // Stub
    }

    override fun setGroupPitch(groupIndex: Int, pitch: Float) {
         // Stub
    }

    override fun setGroupFm(groupIndex: Int, amount: Float) {
         // Stub
    }
    
    override fun setDrive(amount: Float) {}
    override fun setMasterVolume(amount: Float) {}

    override fun setDelay(time: Float, feedback: Float) {}
    
    override fun setDelayTime(index: Int, time: Float) {}
    override fun setDelayFeedback(amount: Float) {}
    override fun setDelayMix(amount: Float) {}
    override fun setDelayModDepth(index: Int, amount: Float) {}
    override fun setDelayModSource(index: Int, isLfo: Boolean) {}
    override fun setDelayLfoWaveform(isTriangle: Boolean) {}

    override fun setHyperLfoFreq(index: Int, frequency: Float) {}
    override fun setHyperLfoMode(andMode: Boolean) {}
    override fun setHyperLfoLink(active: Boolean) {}

    override fun playTestTone(frequency: Float) {
        // TODO("Not yet implemented")
    }

    override fun stopTestTone() {
         // Stub
    }

    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f
}
