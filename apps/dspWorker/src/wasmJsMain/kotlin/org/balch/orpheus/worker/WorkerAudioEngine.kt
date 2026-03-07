@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.worker

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspGraphScheduler
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.dspSampleRate

/**
 * Worker-side AudioEngine implementation.
 *
 * Runs the shared [DspGraphScheduler] inside a Web Worker thread,
 * then sends rendered audio buffers to the AudioWorklet on the main
 * thread via a transferred MessagePort (zero-copy postMessage).
 *
 * Uses a render-ahead strategy identical to [OrpheusAudioEngine]:
 * the worker renders multiple 128-frame buffers per timer tick to
 * maintain a target queue depth in the worklet, absorbing jitter
 * from setInterval and GC pauses.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WorkerAudioEngine constructor() : AudioEngine {
    private val scheduler = DspGraphScheduler()
    private var _isRunning = false
    private var renderTimerId = 0

    private val framesPerBuffer = 128
    private var interleavedBuffer = FloatArray(0)
    private lateinit var stagingBuffer: Float32Array

    private val targetQueueDepth = 24
    private val maxQueueDepth = 32
    private var workletQueueDepth = 0

    private var bufferDurationMs = framesPerBuffer.toDouble() / 48000.0 * 1000.0
    private var cpuLoadAvg = 0f
    private var accumulatedRenderMs = 0.0
    private var accumulatedBudgetMs = 0.0
    private var lastReportedUnderruns = 0

    override fun start() {
        bufferDurationMs = framesPerBuffer.toDouble() / dspSampleRate.toDouble() * 1000.0
        scheduler.sortTopologically()
        scheduler.allocate(framesPerBuffer)
        interleavedBuffer = FloatArray(framesPerBuffer * 2)
        stagingBuffer = jsNewWorkerFloat32Array(framesPerBuffer * 2)

        _isRunning = true

        // Pre-buffer: fill the queue before audio starts playing
        for (i in 0 until targetQueueDepth) {
            renderAndSend()
        }

        // Timer tick at 10ms — each tick renders enough buffers to
        // refill the queue back to targetQueueDepth.
        renderTimerId = jsWorkerSetInterval(10) {
            renderTick()
        }
    }

    private fun renderTick() {
        workletQueueDepth = jsGetWorkerQueueDepth()
        lastReportedUnderruns = jsGetWorkerUnderrunCount()
        val buffersNeeded = (targetQueueDepth - workletQueueDepth).coerceIn(0, maxQueueDepth)
        if (buffersNeeded == 0) return

        val t0 = jsWorkerPerformanceNow()
        for (i in 0 until buffersNeeded) {
            renderAndSend()
        }
        val tickMs = jsWorkerPerformanceNow() - t0
        accumulatedRenderMs += tickMs
        accumulatedBudgetMs += buffersNeeded * bufferDurationMs

        // Update EMA only when enough budget has accumulated (~50ms)
        if (accumulatedBudgetMs >= 50.0) {
            val load = (accumulatedRenderMs / accumulatedBudgetMs).toFloat().coerceIn(0f, 1f)
            cpuLoadAvg += 0.3f * (load - cpuLoadAvg)
            accumulatedRenderMs = 0.0
            accumulatedBudgetMs = 0.0
        }
    }

    private fun renderAndSend() {
        // Run DSP graph — fills interleavedBuffer with [L0,R0,L1,R1,...]
        scheduler.process(interleavedBuffer, framesPerBuffer)

        // Copy interleaved data to persistent JS staging buffer, then
        // let JS do the de-interleave + Transferable post in one call.
        // This avoids creating 2 Float32Arrays per render in WASM and
        // moves the split loop to pure JS (no per-element interop).
        for (i in 0 until framesPerBuffer * 2) {
            stagingBuffer[i] = interleavedBuffer[i]
        }
        jsSplitAndPostToWorklet(stagingBuffer, framesPerBuffer)
    }

    override fun stop() {
        if (renderTimerId != 0) {
            jsWorkerClearInterval(renderTimerId)
            renderTimerId = 0
        }
        _isRunning = false
    }

    override val isRunning: Boolean get() = _isRunning
    override val sampleRate: Int get() = dspSampleRate.toInt()

    override fun addUnit(unit: AudioUnit) {
        if (unit is DspProcessable) scheduler.addUnit(unit)
    }

    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {
        if (unit is DspProcessable) unit.enabled = enabled
    }

    override val lineOutLeft: AudioInput get() = scheduler.masterLeft
    override val lineOutRight: AudioInput get() = scheduler.masterRight

    override fun getCpuLoad(): Float = cpuLoadAvg
    override fun getCurrentTime(): Double = jsWorkerPerformanceNow() / 1000.0

}
