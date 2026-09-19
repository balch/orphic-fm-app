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
class OboeAudioEngine(
    private val bridge: OboeAudioBridge
) : AudioEngine, NativeDspBridge by bridge {

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

    companion object {
        private val log = logging("OboeAudioEngine")
    }
}
