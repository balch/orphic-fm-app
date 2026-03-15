package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Oboe-backed AudioEngine for Android using liborpheus_dsp.
 * Audio rendering happens entirely in C++ — no JNI in the audio callback.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AudioEngine>())
@Inject
class OboeAudioEngine() : AudioEngine, NativeDspBridge {
    private val bridge = OboeAudioBridge()

    init {
        log.info { "OboeAudioEngine created (C++ DSP)" }
    }

    override fun start() {
        if (isRunning) return
        log.info { "start() called" }

        val openResult = bridge.nativeOpen()
        log.info { "nativeOpen() returned $openResult (0=OK)" }
        if (openResult != 0) {
            log.error { "Oboe stream FAILED to open, result=$openResult" }
            return
        }

        val sampleRate = bridge.nativeGetSampleRate()
        val framesPerBuffer = bridge.nativeGetFramesPerBuffer()
        log.info { "Stream opened: sampleRate=$sampleRate, framesPerBuffer=$framesPerBuffer" }
        dspSampleRate = sampleRate.toFloat()

        val startResult = bridge.nativeRequestStart()
        log.info { "nativeRequestStart() returned $startResult (0=OK)" }
        if (startResult != 0) {
            log.error { "Oboe stream FAILED to start, result=$startResult" }
        }
    }

    override fun stop() {
        bridge.nativeStop()
    }

    override val isRunning: Boolean
        get() = bridge.nativeIsRunning()

    override val sampleRate: Int
        get() = bridge.nativeGetSampleRate().let { if (it > 0) it else 48000 }

    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()

    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

    // -- AudioEngine plugin port forwarding (delegates to C++ bridge) ----------
    override fun setPort(uri: String, symbol: String, value: Float) = bridge.nativeSetPort(uri, symbol, value)
    override fun getPort(uri: String, symbol: String): Float = bridge.nativeGetPort(uri, symbol)
    override fun triggerDrum(type: Int, accent: Float) = bridge.nativeTriggerDrum(type, accent)

    /** Access the bridge for parameter control from SynthController delegates. */
    val nativeBridgeImpl: OboeAudioBridge get() = bridge

    // ── NativeDspBridge implementation ──────────────────────
    override fun nativeSetVoiceGate(index: Int, active: Boolean) = bridge.nativeSetVoiceGate(index, active)
    override fun nativeSetVoiceTune(index: Int, tune: Float) = bridge.nativeSetVoiceTune(index, tune)
    override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) = bridge.nativeSetVoiceEngine(index, engineIndex)
    override fun nativeSetVoiceHarmonics(index: Int, value: Float) = bridge.nativeSetVoiceHarmonics(index, value)
    override fun nativeSetVoiceTimbre(index: Int, value: Float) = bridge.nativeSetVoiceTimbre(index, value)
    override fun nativeSetVoiceMorph(index: Int, value: Float) = bridge.nativeSetVoiceMorph(index, value)
    override fun nativeSetVoiceDecay(index: Int, value: Float) = bridge.nativeSetVoiceDecay(index, value)
    override fun nativeSetVoiceActive(index: Int, active: Boolean) = bridge.nativeSetVoiceActive(index, active)
    override fun nativeSetVoiceHold(index: Int, level: Float) = bridge.nativeSetVoiceHold(index, level)
    override fun nativeSetMasterVolume(value: Float) = bridge.nativeSetMasterVolume(value)
    override fun nativeSetDrive(value: Float) = bridge.nativeSetDrive(value)
    override fun nativeSetDelayMix(value: Float) = bridge.nativeSetDelayMix(value)
    override fun nativeSetVibrato(value: Float) = bridge.nativeSetVibrato(value)
    override fun nativeSetVibratoRate(value: Float) = bridge.nativeSetVibratoRate(value)
    override fun nativeSetBend(value: Float) = bridge.nativeSetBend(value)
    override fun nativeSetPort(uri: String, symbol: String, value: Float) = bridge.nativeSetPort(uri, symbol, value)
    override fun nativeGetPort(uri: String, symbol: String): Float = bridge.nativeGetPort(uri, symbol)
    override fun nativeGetMonitor(out: FloatArray) = bridge.nativeGetMonitor(out)
    override fun nativeTriggerDrum(drumIndex: Int, accent: Float) = bridge.nativeTriggerDrum(drumIndex, accent)
    override fun nativeLoadGraph(data: ByteArray): Int = bridge.nativeLoadGraph(data)
    override fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int) =
        bridge.nativeSetAutomation(target, voiceIndex, times, values, count)
    override fun nativeClearAutomation(target: Int, voiceIndex: Int) =
        bridge.nativeClearAutomation(target, voiceIndex)
    override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int) =
        bridge.nativeLoadTtsAudio(samples, sampleRate)
    override fun nativePlayTts() = bridge.nativePlayTts()
    override fun nativeStopTts() = bridge.nativeStopTts()
    override fun nativeIsTtsPlaying(): Int = bridge.nativeIsTtsPlaying()

    companion object {
        private val log = logging("OboeAudioEngine")
    }
}
