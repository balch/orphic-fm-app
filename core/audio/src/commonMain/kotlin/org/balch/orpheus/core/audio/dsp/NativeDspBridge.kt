package org.balch.orpheus.core.audio.dsp

/**
 * Interface for forwarding DSP control calls to a native C++ engine.
 * Implemented by platform-specific AudioEngine implementations (e.g., OboeAudioEngine).
 * When present, DspSynthEngine forwards voice/parameter control through this bridge
 * in addition to (or instead of) the Kotlin DSP graph.
 */
interface NativeDspBridge {
    fun nativeSetVoiceGate(index: Int, active: Boolean)
    fun nativeSetVoiceTune(index: Int, tune: Float)
    fun nativeSetVoiceEngine(index: Int, engineIndex: Int)
    fun nativeSetVoiceHarmonics(index: Int, value: Float)
    fun nativeSetVoiceTimbre(index: Int, value: Float)
    fun nativeSetVoiceMorph(index: Int, value: Float)
    fun nativeSetVoiceDecay(index: Int, value: Float)
    fun nativeSetVoiceActive(index: Int, active: Boolean)
    fun nativeSetVoiceHold(index: Int, level: Float)
    fun nativeSetMasterVolume(value: Float)
    fun nativeSetDrive(value: Float)
    fun nativeSetDelayMix(value: Float)
    fun nativeSetVibrato(value: Float)
    fun nativeSetVibratoRate(value: Float)
    fun nativeSetBend(value: Float)
    fun nativeSetPort(uri: String, symbol: String, value: Float)
    fun nativeGetPort(uri: String, symbol: String): Float
    fun nativeGetMonitor(out: FloatArray)
    fun nativeTriggerDrum(drumIndex: Int, accent: Float)
    fun nativeLoadGraph(data: ByteArray): Int
    fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int)
    fun nativeClearAutomation(target: Int, voiceIndex: Int)
    fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int)
    fun nativePlayTts()
    fun nativeStopTts()
    fun nativeIsTtsPlaying(): Int
}
