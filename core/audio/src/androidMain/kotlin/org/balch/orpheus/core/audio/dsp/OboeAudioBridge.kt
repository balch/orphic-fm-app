package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging

/**
 * Kotlin-side JNI bridge to the C++ OboeEngine + liborpheus_dsp.
 * Audio rendering now happens entirely in C++ — no JNI in the audio path.
 */
class OboeAudioBridge {
    companion object {
        private val log = logging("OboeAudioBridge")
        init {
            log.info { "Loading native library orpheus_oboe..." }
            System.loadLibrary("orpheus_oboe")
            log.info { "Native library loaded successfully" }
        }
    }

    // ── Lifecycle ────────────────────────────────
    external fun nativeOpen(): Int
    external fun nativeRequestStart(): Int
    external fun nativeStop(): Int
    external fun nativeIsRunning(): Boolean
    external fun nativeGetSampleRate(): Int
    external fun nativeGetFramesPerBuffer(): Int
    external fun nativeGetCpuLoad(): Double

    // ── Parameter control (called from UI thread) ─
    external fun nativeSetPort(uri: String, symbol: String, value: Float)
    external fun nativeGetPort(uri: String, symbol: String): Float
    external fun nativeSetVoiceGate(index: Int, active: Boolean)
    external fun nativeSetVoiceTune(index: Int, tune: Float)
    external fun nativeSetVoiceEngine(index: Int, engineIndex: Int)
    external fun nativeSetVoiceHarmonics(index: Int, value: Float)
    external fun nativeSetVoiceTimbre(index: Int, value: Float)
    external fun nativeSetVoiceMorph(index: Int, value: Float)
    external fun nativeSetVoiceDecay(index: Int, value: Float)
    external fun nativeSetVoiceActive(index: Int, active: Boolean)
    external fun nativeSetVoiceHold(index: Int, level: Float)
    external fun nativeTriggerDrum(drumIndex: Int, accent: Float)
    external fun nativeSetMasterVolume(value: Float)
    external fun nativeSetDrive(value: Float)
    external fun nativeSetDelayMix(value: Float)
    external fun nativeSetVibrato(value: Float)
    external fun nativeSetVibratoRate(value: Float)
    external fun nativeSetBend(value: Float)
    external fun nativeGetMonitor(out: FloatArray)
    external fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int
    external fun nativeLoadGraph(serialized: ByteArray): Int
    external fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int)
    external fun nativeClearAutomation(target: Int, voiceIndex: Int)
    external fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int)
    external fun nativePlayTts()
    external fun nativeStopTts()
    external fun nativeIsTtsPlaying(): Int
}
