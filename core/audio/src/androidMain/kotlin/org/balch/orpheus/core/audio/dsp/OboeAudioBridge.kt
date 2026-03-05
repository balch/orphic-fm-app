package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging

/**
 * Kotlin-side JNI bridge to the C++ OboeEngine.
 * The native library calls [renderAudio] from the Oboe audio thread.
 */
class OboeAudioBridge(private val scheduler: OboeGraphScheduler) {
    companion object {
        private val log = logging("OboeAudioBridge")
        init {
            log.info { "Loading native library orpheus_oboe..." }
            System.loadLibrary("orpheus_oboe")
            log.info { "Native library loaded successfully" }
        }
    }

    /** Called from C++ onAudioReady — must be allocation-free. */
    fun renderAudio(outputBuffer: FloatArray, numFrames: Int) {
        scheduler.process(outputBuffer, numFrames)
    }

    /** Open the Oboe stream (does NOT start audio). */
    external fun nativeOpen(): Int
    /** Start audio playback. Call after buffers are allocated. */
    external fun nativeRequestStart(): Int
    external fun nativeStop(): Int
    external fun nativeIsRunning(): Boolean
    external fun nativeGetSampleRate(): Int
    external fun nativeGetFramesPerBuffer(): Int
    external fun nativeGetCpuLoad(): Double
}
