package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * WASM AudioEngine implementation.
 *
 * Runs the shared [DspGraphScheduler] from commonMain on the main thread,
 * then sends rendered audio buffers to an AudioWorkletProcessor via
 * MessagePort.postMessage() with Transferable Float32Arrays (zero-copy).
 *
 * Uses a render-ahead strategy: the main thread renders multiple buffers
 * per timer tick to maintain a target queue depth in the worklet, absorbing
 * jitter from setInterval and GC pauses.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OrpheusAudioEngine @Inject constructor() : AudioEngine {
    private val scheduler = DspGraphScheduler()
    private var audioContext: AudioContext? = null
    private var workletNode: AudioWorkletNode? = null
    private var renderTimerId: Int = 0
    private var _isRunning = false

    // Pre-allocated buffer for interleaved L/R output from DspGraphScheduler
    private var interleavedBuffer = FloatArray(0)
    private val framesPerBuffer = 128 // Web Audio render quantum

    // Render-ahead buffering: target 16 buffers (~43ms at 48kHz).
    // Generous depth absorbs main-thread jitter from Compose UI, GC, and setInterval.
    private val targetQueueDepth = 16
    private val maxQueueDepth = 24 // safety cap to prevent runaway buffering
    private var workletQueueDepth = 0

    // CPU load measurement: accumulate render time over multiple ticks to overcome
    // performance.now() precision limits (1ms without cross-origin isolation)
    private var bufferDurationMs = framesPerBuffer.toDouble() / 48000.0 * 1000.0 // ~2.67ms
    private var cpuLoadAvg = 0f
    private var accumulatedRenderMs = 0.0
    private var accumulatedBudgetMs = 0.0

    // Underrun tracking from worklet
    private var lastReportedUnderruns = 0

    override fun start() {
        if (audioContext != null) return // Already started or starting
        val ctx = createAudioContext()
        audioContext = ctx
        ctx.resume()

        // Set global sample rate from actual AudioContext before allocating DSP
        dspSampleRate = ctx.sampleRate
        bufferDurationMs = framesPerBuffer.toDouble() / ctx.sampleRate.toDouble() * 1000.0

        // Sort the DSP graph and allocate all buffers
        scheduler.sortTopologically()
        scheduler.allocate(framesPerBuffer)
        interleavedBuffer = FloatArray(framesPerBuffer * 2)

        // Load AudioWorklet module, then start the render loop
        loadWorklet(ctx)
    }

    private fun loadWorklet(ctx: AudioContext) {
        val promise = jsAddWorkletModule(ctx, "dsp-output-processor.js")
        jsPromiseThen(promise) {
            val node = jsCreateWorkletNode(ctx, "dsp-output-processor")
            node.connect(ctx.destination)
            workletNode = node

            // Set up queue depth listener (stores in globalThis, avoids callback issues)
            jsSetupQueueDepthListener(node)

            _isRunning = true

            // Pre-buffer: fill the queue before audio starts playing
            for (i in 0 until targetQueueDepth) {
                renderAndSend(node)
            }

            // Timer tick at 10ms — each tick renders enough buffers to
            // refill the queue back to targetQueueDepth.
            renderTimerId = jsSetInterval(10) {
                renderTick()
            }
        }
    }

    private fun renderTick() {
        val node = workletNode ?: return
        // Poll queue depth and underrun count from globalThis (set by worklet port listener)
        workletQueueDepth = jsGetQueueDepth()
        lastReportedUnderruns = jsGetUnderrunCount()
        val buffersNeeded = (targetQueueDepth - workletQueueDepth).coerceIn(0, maxQueueDepth)
        if (buffersNeeded == 0) return

        // Time the entire tick — individual 128-frame renders are too fast
        // for performance.now() precision (browsers reduce to 5-100μs for Spectre mitigations)
        val t0 = jsPerformanceNow()
        for (i in 0 until buffersNeeded) {
            renderAndSend(node)
        }
        val tickMs = jsPerformanceNow() - t0
        accumulatedRenderMs += tickMs
        accumulatedBudgetMs += buffersNeeded * bufferDurationMs

        // Update EMA only when enough budget has accumulated (~50ms)
        // to ensure render time is well above performance.now() precision floor
        if (accumulatedBudgetMs >= 50.0) {
            val load = (accumulatedRenderMs / accumulatedBudgetMs).toFloat().coerceIn(0f, 1f)
            cpuLoadAvg += 0.3f * (load - cpuLoadAvg)
            accumulatedRenderMs = 0.0
            accumulatedBudgetMs = 0.0
        }
    }

    private fun renderAndSend(node: AudioWorkletNode) {
        // Run DSP graph — fills interleavedBuffer with [L0,R0,L1,R1,...]
        scheduler.process(interleavedBuffer, framesPerBuffer)

        // De-interleave into separate L/R Float32Arrays
        // New arrays each call because postMessage transfers ownership
        val left = jsNewFloat32Array(framesPerBuffer)
        val right = jsNewFloat32Array(framesPerBuffer)
        for (i in 0 until framesPerBuffer) {
            left[i] = interleavedBuffer[i * 2]
            right[i] = interleavedBuffer[i * 2 + 1]
        }

        // Send to worklet via MessagePort with Transferable (zero-copy)
        jsPostBufferToWorklet(node, left, right)
    }

    override fun stop() {
        if (renderTimerId != 0) {
            jsClearInterval(renderTimerId)
            renderTimerId = 0
        }
        workletNode?.disconnect()
        workletNode = null
        audioContext?.close()
        audioContext = null
        _isRunning = false
    }

    override val isRunning: Boolean get() = _isRunning
    override val sampleRate: Int get() = audioContext?.sampleRate?.toInt() ?: 48000

    override fun addUnit(unit: AudioUnit) {
        if (unit is DspProcessable) {
            scheduler.addUnit(unit)
        }
    }

    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {
        if (unit is DspProcessable) {
            unit.enabled = enabled
        }
    }

    override val lineOutLeft: AudioInput get() = scheduler.masterLeft
    override val lineOutRight: AudioInput get() = scheduler.masterRight

    override fun getCpuLoad(): Float = cpuLoadAvg
    override fun getCurrentTime(): Double = audioContext?.currentTime ?: 0.0

}
