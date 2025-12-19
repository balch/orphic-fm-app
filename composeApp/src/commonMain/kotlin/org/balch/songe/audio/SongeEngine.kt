package org.balch.songe.audio

interface SongeEngine {
    fun start()
    fun stop()
    
    // Voice Control
    fun setVoiceTune(index: Int, tune: Float)
    fun setVoiceGate(index: Int, active: Boolean)
    fun setVoiceFeedback(index: Int, amount: Float)
    
    // Group Control
    fun setGroupPitch(groupIndex: Int, pitch: Float) // 0 for 1-4, 1 for 5-8
    fun setGroupFm(groupIndex: Int, amount: Float)
    
    // Global FX
    fun setDrive(amount: Float)
    fun setDelay(time: Float, feedback: Float)
    
    // Test/Debug
    fun playTestTone(frequency: Float = 440f)
    fun stopTestTone()
    
    // Monitoring
    fun getPeak(): Float
    fun getCpuLoad(): Float
}
