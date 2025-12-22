package org.balch.songe.audio

interface SongeEngine {
    fun start()
    fun stop()
    
    // Voice Control
    fun setVoiceTune(index: Int, tune: Float)
    fun setVoiceGate(index: Int, active: Boolean)
    fun setVoiceFeedback(index: Int, amount: Float)
    fun setVoiceFmDepth(index: Int, amount: Float) // FM modulation from pair
    fun setVoiceEnvelopeSpeed(index: Int, speed: Float) // 0=Fast, 1=Slow (continuous)
    fun setPairSharpness(pairIndex: Int, sharpness: Float) // Waveform (0=tri, 1=sq) per pair
    

    // Group Control (Quad 1-4, 5-8)
    fun setQuadPitch(quadIndex: Int, pitch: Float) // 0-1, 0.5=Unity
    fun setQuadHold(quadIndex: Int, amount: Float) // 0-1, VCA bias
    
    // Global
    fun setDrive(amount: Float)
    fun setDistortionMix(amount: Float) // 0=clean, 1=distorted (post-delay)
    fun setMasterVolume(amount: Float)
    
    // Delay Controls
    fun setDelayTime(index: Int, time: Float) // 0 or 1
    fun setDelayFeedback(amount: Float)
    fun setDelayMix(amount: Float)
    
    // Delay Modulation
    fun setDelayModDepth(index: Int, amount: Float)
    fun setDelayModSource(index: Int, isLfo: Boolean) // true=LFO, false=Self
    fun setDelayLfoWaveform(isTriangle: Boolean) // true=Triangle, false=Square (AND)
    
    @Deprecated("Use granular setDelayTime/Feedback instead")
    fun setDelay(time: Float, feedback: Float)
    
    // Hyper LFO
    fun setHyperLfoFreq(index: Int, frequency: Float) // 0=A, 1=B
    fun setHyperLfoMode(mode: Int) // 0=AND, 1=OFF, 2=OR
    fun setHyperLfoLink(active: Boolean)
    
    // Duo Mod Source
    fun setDuoModSource(duoIndex: Int, source: ModSource)
    
    // Advanced FM
    fun setFmStructure(crossQuad: Boolean) // true = 34>56, 78>12 routing
    fun setTotalFeedback(amount: Float) // 0-1, output→LFO feedback
    fun setVibrato(amount: Float) // 0-1, global pitch wobble depth
    fun setVoiceCoupling(amount: Float) // 0-1, partner envelope→frequency depth
    
    // Test/Debug
    fun playTestTone(frequency: Float = 440f)
    fun stopTestTone()
    
    // Monitoring
    fun getPeak(): Float
    fun getCpuLoad(): Float
    
    // Reactive monitoring flows (emit at ~100ms intervals)
    val peakFlow: kotlinx.coroutines.flow.StateFlow<Float>
    val cpuLoadFlow: kotlinx.coroutines.flow.StateFlow<Float>
}

enum class ModSource {
    VOICE_FM,
    OFF,
    LFO
}
