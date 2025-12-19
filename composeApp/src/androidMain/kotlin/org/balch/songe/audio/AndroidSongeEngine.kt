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

    override fun setGroupPitch(groupIndex: Int, pitch: Float) {
         // Stub
    }

    override fun setGroupFm(groupIndex: Int, amount: Float) {
         // Stub
    }
    
    override fun setDrive(amount: Float) {
         // Stub
    }

    override fun setDelay(time: Float, feedback: Float) {
         // Stub
    }

    override fun playTestTone(frequency: Float) {
         // Stub
    }

    override fun stopTestTone() {
         // Stub
    }

    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f
}
