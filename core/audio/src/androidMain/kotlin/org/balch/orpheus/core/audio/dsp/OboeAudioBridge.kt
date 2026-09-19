package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Kotlin-side JNI bridge to the C++ OboeEngine + liborpheus_dsp.
 * Audio rendering now happens entirely in C++ — no JNI in the audio path.
 */
@SingleIn(AppScope::class)
@Inject
class OboeAudioBridge : NativeDspBridge {
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
    external override fun nativeGetXRunCount(): Int

    // ── Parameter control (called from UI thread) ─
    external override fun nativeSetPort(uri: String, symbol: String, value: Float)
    external override fun nativeGetPort(uri: String, symbol: String): Float
    external override fun nativeSetVoiceGate(index: Int, active: Boolean)
    external override fun nativeSetVoiceTune(index: Int, tune: Float)
    external override fun nativeSetVoiceEngine(index: Int, engineIndex: Int)
    external override fun nativeSetVoiceHarmonics(index: Int, value: Float)
    external override fun nativeSetVoiceTimbre(index: Int, value: Float)
    external override fun nativeSetVoiceMorph(index: Int, value: Float)
    external override fun nativeSetVoiceDecay(index: Int, value: Float)
    external override fun nativeSetVoiceActive(index: Int, active: Boolean)
    external override fun nativeSetVoiceHold(index: Int, level: Float)
    external override fun nativeTriggerDrum(drumIndex: Int, accent: Float)
    external override fun nativeSetMasterVolume(value: Float)
    external override fun nativeMasterFade(target: Float, samples: Int, curve: Int)
    external override fun nativeMasterTapeStop(samples: Int)
    external override fun nativeMasterScratch(samples: Int)
    external override fun nativeMasterFilter(samples: Int)
    external override fun nativeMasterVolumeNow(): Float
    external override fun nativeSetDrive(value: Float)
    external override fun nativeSetDelayMix(value: Float)
    external override fun nativeSetVibrato(value: Float)
    external override fun nativeSetVibratoRate(value: Float)
    external override fun nativeSetBend(value: Float)
    external override fun nativeGetMonitor(out: FloatArray)
    external override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int
    external override fun nativeGetSpectrum(bands: FloatArray): Int
    external override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray)
    external override fun nativeLoadGraph(data: ByteArray): Int
    external override fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int)
    external override fun nativeClearAutomation(target: Int, voiceIndex: Int)
    external override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int)
    external override fun nativePlayTts()
    external override fun nativeStopTts()
    external override fun nativeIsTtsPlaying(): Int
    external override fun nativeGetPulsarViz(
        gatesOut: BooleanArray,
        velocitiesOut: FloatArray,
        playheadsOut: IntArray,
        stepCountsOut: IntArray,
    )
    external override fun nativeGetPulsarActiveEngines(out: IntArray)
    external override fun nativeGetPulsarArrangement(out: IntArray)

    /**
     * Register a Runnable that the C++ side invokes after recreating the DSP
     * engine (e.g. when Oboe rebuilds the audio stream on a route change with
     * a different sample rate). The Runnable runs on whatever thread native
     * code chooses — Kotlin must marshal to a safe scope before doing real
     * work. Pass null to clear.
     */
    external fun nativeSetEngineRecreatedCallback(callback: Runnable?)

    override fun setOnEngineRecreatedCallback(callback: (() -> Unit)?) {
        nativeSetEngineRecreatedCallback(callback?.let { Runnable(it) })
    }
}
