package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Oboe-backed AudioEngine for Android. Replaces OrpheusAudioEngine (JSyn).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeAudioEngine @Inject constructor() : AudioEngine {
    private val scheduler = OboeGraphScheduler()
    private val bridge = OboeAudioBridge(scheduler)

    init {
        log.info { "OboeAudioEngine created" }
    }

    override fun start() {
        if (isRunning) return // Already started
        log.info { "start() called, ${scheduler.unitCount} units registered" }

        // Sort the graph using Tarjan's SCC algorithm (handles feedback cycles)
        scheduler.sortTopologically()

        // 1. Open stream (no audio yet)
        val openResult = bridge.nativeOpen()
        log.info { "nativeOpen() returned $openResult (0=OK)" }
        if (openResult != 0) {
            log.error { "Oboe stream FAILED to open, result=$openResult" }
            return
        }

        // 2. Allocate Kotlin buffers BEFORE starting audio
        val framesPerBuffer = bridge.nativeGetFramesPerBuffer()
        val sampleRate = bridge.nativeGetSampleRate()
        log.info { "Stream opened: sampleRate=$sampleRate, framesPerBuffer=$framesPerBuffer" }
        dspSampleRate = sampleRate.toFloat()
        scheduler.allocate(framesPerBuffer.coerceAtLeast(256))

        // 3. Now start — callback can fire immediately, buffers are ready
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
        if (unit is OboeProcessable) {
            scheduler.addUnit(unit)
        }
    }

    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {
        if (unit is OboeProcessable) {
            unit.enabled = enabled
            // Zero primary output buffer when disabling to prevent stale audio
            if (!enabled) {
                val out = unit.output
                if (out is OboeAudioOutput) {
                    out.getBuffer().fill(0f)
                }
            }
        }
    }

    // Expose scheduler's master inputs directly — plugins connect their
    // final outputs here, and the scheduler reads from them in process().
    override val lineOutLeft: AudioInput
        get() = scheduler.masterLeft

    override val lineOutRight: AudioInput
        get() = scheduler.masterRight

    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()

    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

    companion object {
        private val log = logging("OboeAudioEngine")
    }
}
