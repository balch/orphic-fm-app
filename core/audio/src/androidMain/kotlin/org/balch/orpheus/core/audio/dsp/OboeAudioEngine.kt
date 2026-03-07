package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Oboe-backed AudioEngine for Android using liborpheus_dsp.
 * Audio rendering happens entirely in C++ — no JNI in the audio callback.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeAudioEngine @Inject constructor() : AudioEngine {
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

    override fun addUnit(unit: AudioUnit) {
        // No-op: C++ engine manages its own units
    }

    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {
        // No-op: C++ engine manages its own units
    }

    override val lineOutLeft: AudioInput
        get() = NoOpAudioInput

    override val lineOutRight: AudioInput
        get() = NoOpAudioInput

    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()

    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

    /** Access the bridge for parameter control from SynthController delegates. */
    val nativeBridge: OboeAudioBridge get() = bridge

    companion object {
        private val log = logging("OboeAudioEngine")
    }
}

/** Placeholder AudioInput — C++ handles all routing internally. */
private object NoOpAudioInput : AudioInput {
    override fun set(value: Double) {}
    override fun disconnectAll() {}
}
