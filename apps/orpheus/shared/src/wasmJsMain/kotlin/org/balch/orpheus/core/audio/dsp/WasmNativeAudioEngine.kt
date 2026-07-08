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

    private var lastMasterVolume: Float = 0.7f
    private var fadeTimerId: Int = 0

    override fun nativeSetMasterVolume(value: Float) {
        lastMasterVolume = value
        workerProxy.setMasterVolume(value)
    }

    private fun scheduleFade(target: Float, durationMs: Int) {
        if (fadeTimerId != 0) jsClearInterval(fadeTimerId)
        val steps = (durationMs / FADE_STEP_MS).coerceIn(1, 50)
        val start = lastMasterVolume
        var step = 0
        fadeTimerId = jsSetInterval(FADE_STEP_MS) {
            step++
            val t = (step.toFloat() / steps).coerceAtMost(1f)
            val v = start + (target - start) * t
            lastMasterVolume = v
            workerProxy.setMasterVolume(v)
            if (step >= steps) {
                jsClearInterval(fadeTimerId)
                fadeTimerId = 0
            }
        }
    }

    // WASM Worker doesn't yet pipe fade/tape-stop commands to the C++ engine.
    // Approximate with a stepped ramp via JS setTimeout for basic smoothing.
    override fun nativeMasterFade(target: Float, samples: Int, curve: Int) {
        val durationMs = (samples.toFloat() / (workerProxy.sampleRate.coerceAtLeast(1)).toFloat() * 1000f).toInt()
        scheduleFade(target, durationMs.coerceAtLeast(1))
    }

    override fun nativeMasterTapeStop(samples: Int) {
        val durationMs = (samples.toFloat() / (workerProxy.sampleRate.coerceAtLeast(1)).toFloat() * 1000f).toInt()
        scheduleFade(0f, durationMs.coerceAtLeast(1))
    }

    override fun nativeMasterScratch(samples: Int) {
        // WASM Worker doesn't yet support the scratch noise generator.
        // No-op: the scratch is purely additive so skipping it is silent.
    }

    override fun nativeMasterFilter(samples: Int) {
        // WASM Worker doesn't yet support the filter sweep.
        // No-op: sweep is in-place, so skipping it passes audio through clean.
    }

    override fun nativeMasterVolumeNow(): Float = lastMasterVolume

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
    override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int = 0
    override fun nativeGetSpectrum(bands: FloatArray): Int = 0
    override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) { /* WASM: not yet implemented */ }
    override fun nativeGetPulsarViz(
        gatesOut: BooleanArray, velocitiesOut: FloatArray,
        playheadsOut: IntArray, stepCountsOut: IntArray,
    ) { /* WASM: not yet implemented */ }
    override fun nativeGetPulsarActiveEngines(out: IntArray) { for (i in out.indices) out[i] = -1 }
    override fun nativeGetPulsarArrangement(out: IntArray) { /* WASM: not yet implemented */ }

    companion object {
        private val log = logging("WasmNativeAudioEngine")
        private const val FADE_STEP_MS = 10
    }
}
