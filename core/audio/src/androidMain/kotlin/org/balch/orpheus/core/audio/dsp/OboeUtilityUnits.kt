package org.balch.orpheus.core.audio.dsp

import kotlin.math.floor
import kotlin.math.min

private const val UTIL_SAMPLE_RATE = DSP_SAMPLE_RATE
private const val BAKE_SIZE = 1024

/**
 * Kotlin-native utility DSP units for the Oboe audio backend.
 * Zero-allocation process() methods for real-time audio thread safety.
 */

/**
 * Precision pulse clock generator.
 * Outputs 0.0 or 1.0 pulses at the specified frequency.
 */
class OboeClockUnit : ClockUnit, OboeProcessable {
    @Volatile override var enabled = true
    private val freq = OboeAudioInput("Clock.freq")
    private val pw = OboeAudioInput("Clock.pulseWidth")
    private val out = OboeAudioOutput("Clock.out")

    override val frequency: AudioInput = freq
    override val pulseWidth: AudioInput = pw
    override val output: AudioOutput = out

    private var phase = 0f // 0..1 range

    init {
        pw.set(0.5) // default 50% duty cycle
    }

    override fun process(numFrames: Int) {
        val f = freq.getBuffer()
        val w = pw.getBuffer()
        val o = out.getBuffer()
        var p = phase
        for (i in 0 until numFrames) {
            // Pulse: 1.0 when phase < width, -1.0 otherwise, then scale to 0..1
            val pulse = if (p < w[i]) 1f else -1f
            o[i] = pulse * 0.5f + 0.5f
            p += f[i] / UTIL_SAMPLE_RATE
            p -= floor(p)
        }
        phase = p
    }

    override fun allocateBuffers(maxFrames: Int) {
        freq.allocate(maxFrames)
        pw.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(freq, pw)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

// Automation playback modes
private const val MODE_ONCE = 0
private const val MODE_LOOP = 1
private const val MODE_PING_PONG = 2

/**
 * Automation curve player that bakes keypoints into a lookup table
 * and plays them back with linear interpolation.
 */
class OboeAutomationPlayer : AutomationPlayer, OboeProcessable {
    @Volatile override var enabled = true
    private val out = OboeAudioOutput("Automation.out")
    override val output: AudioOutput = out

    // Baked lookup table
    private var bakedData = FloatArray(BAKE_SIZE)
    private var pathTimes = FloatArray(0)
    private var pathValues = FloatArray(0)
    private var pathCount = 0

    // Playback state
    private var position = 0f       // 0..BAKE_SIZE fractional position
    private var rate = 0f           // samples per process sample
    private var playing = false
    private var mode = MODE_ONCE
    private var direction = 1f      // 1=forward, -1=backward (for ping-pong)
    private var lastValue = 0f

    override fun setPath(times: FloatArray, values: FloatArray, count: Int) {
        pathTimes = times.copyOf(count)
        pathValues = values.copyOf(count)
        pathCount = count
        bake()
    }

    override fun setDuration(seconds: Float) {
        val s = if (seconds > 0.001f) seconds else 0.001f
        rate = BAKE_SIZE.toFloat() / (s * UTIL_SAMPLE_RATE)
    }

    override fun setMode(mode: Int) {
        this.mode = mode
    }

    override fun play() {
        playing = true
        position = 0f
        direction = 1f
    }

    override fun stop() {
        playing = false
    }

    override fun reset() {
        position = 0f
        direction = 1f
    }

    /**
     * Bake keypoints into a fixed-size lookup table using linear interpolation.
     */
    private fun bake() {
        val data = bakedData
        if (pathCount == 0) {
            data.fill(0f)
            return
        }
        if (pathCount == 1) {
            data.fill(pathValues[0])
            return
        }

        val times = pathTimes
        val values = pathValues
        val count = pathCount
        var segIdx = 0

        for (i in 0 until BAKE_SIZE) {
            val t = i.toFloat() / (BAKE_SIZE - 1).toFloat() // normalized 0..1

            // Advance segment index
            while (segIdx < count - 2 && times[segIdx + 1] < t) {
                segIdx++
            }

            // Linear interpolation within segment
            val t0 = times[segIdx]
            val t1 = times[min(segIdx + 1, count - 1)]
            val v0 = values[segIdx]
            val v1 = values[min(segIdx + 1, count - 1)]

            val segLen = t1 - t0
            val frac = if (segLen > 0.0001f) ((t - t0) / segLen).coerceIn(0f, 1f) else 0f
            data[i] = v0 + (v1 - v0) * frac
        }
    }

    override fun process(numFrames: Int) {
        val o = out.getBuffer()
        val data = bakedData

        if (!playing) {
            // Output last value when not playing
            for (i in 0 until numFrames) {
                o[i] = lastValue
            }
            return
        }

        for (i in 0 until numFrames) {
            // Read with linear interpolation from baked data
            val pos = position.coerceIn(0f, (BAKE_SIZE - 1).toFloat())
            val idx = pos.toInt()
            val frac = pos - idx
            val s0 = data[idx]
            val s1 = if (idx + 1 < BAKE_SIZE) data[idx + 1] else s0
            val value = s0 + (s1 - s0) * frac
            o[i] = value
            lastValue = value

            // Advance position
            position += rate * direction

            // Handle end-of-path
            when (mode) {
                MODE_ONCE -> {
                    if (position >= BAKE_SIZE) {
                        position = (BAKE_SIZE - 1).toFloat()
                        playing = false
                        // Fill remaining frames with last value
                        for (j in (i + 1) until numFrames) {
                            o[j] = lastValue
                        }
                        return
                    }
                }
                MODE_LOOP -> {
                    if (position >= BAKE_SIZE) {
                        position -= BAKE_SIZE
                    }
                }
                MODE_PING_PONG -> {
                    if (position >= BAKE_SIZE) {
                        position = (BAKE_SIZE - 1).toFloat()
                        direction = -1f
                    } else if (position < 0f) {
                        position = 0f
                        direction = 1f
                    }
                }
            }
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        out.allocate(maxFrames)
    }

    private val outputPorts = listOf(out)
    override fun getInputPorts(): List<OboeAudioInput> = emptyList()
    override fun getOutputPorts() = outputPorts
}
