@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * WASM AudioEngine backed by the C++ DSP Web Worker via [DspWorkerProxy].
 *
 * Audio rendering happens entirely in the Worker thread (C++ compiled to WASM).
 * The main thread only handles AudioContext/AudioWorklet setup and command forwarding.
 *
 * Uses the C++ Worker path as the default WASM audio engine.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AudioEngine>())
class WasmNativeAudioEngine : AudioEngine, NativeDspBridge {

    private val workerProxy = DspWorkerProxy()

    init {
        log.info { "WasmNativeAudioEngine created (C++ Worker DSP)" }
    }

    override fun start() {
        if (workerProxy.isReady) return
        log.info { "start() — initializing C++ Worker audio" }
        workerProxy.start(graphBytes = null)
        // dspSampleRate is set inside DspWorkerProxy.start() from the AudioContext
    }

    override fun stop() {
        log.info { "stop() — shutting down C++ Worker audio" }
        workerProxy.stop()
    }

    override val isRunning: Boolean get() = workerProxy.isReady

    override val sampleRate: Int get() = workerProxy.sampleRate

    override fun getCpuLoad(): Float = workerProxy.getCpuLoad()

    override fun getCurrentTime(): Double = jsPerformanceNow() / 1000.0

    // ── AudioEngine plugin port forwarding ────────────────────────────────────

    override fun setPort(uri: String, symbol: String, value: Float) =
        workerProxy.setPort(uri, symbol, value)

    override fun getPort(uri: String, symbol: String): Float = 0f

    override fun triggerDrum(type: Int, accent: Float) =
        workerProxy.triggerDrum(type, accent)

    // ── NativeDspBridge implementation ───────────────────────────────────────

    override fun nativeSetVoiceGate(index: Int, active: Boolean) =
        workerProxy.setVoiceGate(index, active)

    override fun nativeSetVoiceTune(index: Int, tune: Float) =
        workerProxy.setVoiceTune(index, tune)

    override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) =
        jsSendVoiceEngineCmd(index, engineIndex)

    override fun nativeSetVoiceHarmonics(index: Int, value: Float) =
        jsSendVoiceHarmonicsCmd(index, value)

    override fun nativeSetVoiceTimbre(index: Int, value: Float) =
        jsSendVoiceTimbreCmd(index, value)

    override fun nativeSetVoiceMorph(index: Int, value: Float) =
        jsSendVoiceMorphCmd(index, value)

    override fun nativeSetVoiceDecay(index: Int, value: Float) =
        jsSendVoiceDecayCmd(index, value)

    override fun nativeSetVoiceActive(index: Int, active: Boolean) =
        jsSendVoiceActiveCmd(index, active)

    override fun nativeSetVoiceHold(index: Int, level: Float) =
        jsSendVoiceHoldCmd(index, level)

    override fun nativeSetMasterVolume(value: Float) =
        workerProxy.setMasterVolume(value)

    override fun nativeSetDrive(value: Float) =
        workerProxy.setDrive(value)

    override fun nativeSetDelayMix(value: Float) =
        workerProxy.setDelayMix(value)

    override fun nativeSetVibrato(value: Float) =
        workerProxy.setVibrato(value)

    override fun nativeSetVibratoRate(value: Float) =
        workerProxy.setVibratoRate(value)

    override fun nativeSetBend(value: Float) =
        workerProxy.setBend(value)

    override fun nativeSetPort(uri: String, symbol: String, value: Float) =
        workerProxy.setPort(uri, symbol, value)

    override fun nativeGetPort(uri: String, symbol: String): Float = 0f

    override fun nativeGetMonitor(out: FloatArray) {
        // No-op: monitoring reported via Worker messages (getCpuLoad() above)
    }

    override fun nativeTriggerDrum(drumIndex: Int, accent: Float) =
        workerProxy.triggerDrum(drumIndex, accent)

    override fun nativeLoadGraph(data: ByteArray): Int {
        jsSendLoadGraphCmd(data)
        return 0
    }

    override fun nativeSetAutomation(
        target: Int,
        voiceIndex: Int,
        times: FloatArray,
        values: FloatArray,
        count: Int
    ) = jsSendSetAutomationCmd(target, voiceIndex, times, values, count)

    override fun nativeClearAutomation(target: Int, voiceIndex: Int) =
        jsSendClearAutomationCmd(target, voiceIndex)

    override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int) =
        jsSendLoadTtsAudioCmd(samples, sampleRate)

    override fun nativePlayTts() = jsSendPlayTtsCmd()

    override fun nativeStopTts() = jsSendStopTtsCmd()

    override fun nativeIsTtsPlaying(): Int = jsGetTtsPlaying()

    companion object {
        private val log = logging("WasmNativeAudioEngine")
    }
}
