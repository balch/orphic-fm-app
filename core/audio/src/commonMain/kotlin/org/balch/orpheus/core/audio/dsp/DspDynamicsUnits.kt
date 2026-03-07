package org.balch.orpheus.core.audio.dsp

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh

/**
 * Kotlin-native dynamics DSP processing units.
 * Zero-allocation process() methods for real-time audio thread safety.
 */

// ---- Envelope States ----
private const val ENV_IDLE = 0
private const val ENV_ATTACK = 1
private const val ENV_DECAY = 2
private const val ENV_SUSTAIN = 3
private const val ENV_RELEASE = 4

/**
 * ADSR envelope generator with gate edge detection.
 * Attack is linear; decay and release are exponential.
 */
class DspEnvelope : Envelope, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("Envelope.gate")
    private val out = DspAudioOutput("Envelope.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    private var state = ENV_IDLE
    private var level = 0f
    private var gateWasOn = false

    // Coefficients — written from UI thread, read from audio thread
    @Volatile private var attackRate = 1f / (0.01f * dspSampleRate)
    @Volatile private var decayCoeff = exp(-1f / (0.1f * dspSampleRate))
    @Volatile private var sustainLevel = 0.5f
    @Volatile private var releaseCoeff = exp(-1f / (0.1f * dspSampleRate))

    override fun setAttack(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f) // minimum 1ms to avoid division by zero
        attackRate = 1f / (s * dspSampleRate)
    }

    override fun setDecay(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f)
        decayCoeff = exp(-1f / (s * dspSampleRate))
    }

    override fun setSustain(level: Double) {
        sustainLevel = level.toFloat().coerceIn(0f, 1f)
    }

    override fun setRelease(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f)
        releaseCoeff = exp(-1f / (s * dspSampleRate))
    }

    override fun process(numFrames: Int) {
        val gate = inp.getBuffer()
        val o = out.getBuffer()
        for (i in 0 until numFrames) {
            val gateOn = gate[i] > 0f

            // Edge detection: gate just turned on
            if (gateOn && !gateWasOn) {
                state = ENV_ATTACK
            }
            // Edge detection: gate just turned off
            if (!gateOn && gateWasOn) {
                state = ENV_RELEASE
            }
            gateWasOn = gateOn

            when (state) {
                ENV_ATTACK -> {
                    level += attackRate
                    if (level >= 1f) {
                        level = 1f
                        state = ENV_DECAY
                    }
                }
                ENV_DECAY -> {
                    // Exponential decay toward sustain level
                    level = sustainLevel + (level - sustainLevel) * decayCoeff
                    // Close enough to sustain -> transition
                    if (level - sustainLevel < 0.0001f) {
                        level = sustainLevel
                        state = ENV_SUSTAIN
                    }
                }
                ENV_SUSTAIN -> {
                    level = sustainLevel
                }
                ENV_RELEASE -> {
                    level *= releaseCoeff
                    if (level < 0.0001f) {
                        level = 0f
                        state = ENV_IDLE
                    }
                }
                ENV_IDLE -> {
                    level = 0f
                }
            }
            o[i] = level
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

/**
 * Linear ramp for smooth parameter transitions.
 * Prevents zipper noise by linearly interpolating toward the target value.
 */
class DspLinearRamp : LinearRamp, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("LinearRamp.target")
    private val tim = DspAudioInput("LinearRamp.time", smoothed = true)
    private val out = DspAudioOutput("LinearRamp.out")

    override val input: AudioInput = inp
    override val time: AudioInput = tim
    override val output: AudioOutput = out

    private var current = 0f

    override fun process(numFrames: Int) {
        val target = inp.getBuffer()
        val t = tim.getBuffer()
        val o = out.getBuffer()
        var c = current
        for (i in 0 until numFrames) {
            val rampTime = max(t[i], 0.001f) // minimum 1ms
            val rate = 1f / (rampTime * dspSampleRate)
            val diff = target[i] - c
            c += diff.coerceIn(-rate, rate)
            o[i] = c
        }
        current = c
    }

    override fun allocateBuffers(maxFrames: Int) {
        inp.allocate(maxFrames)
        tim.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inp, tim)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

/**
 * Envelope follower with exponential decay.
 * Tracks the peak amplitude of the input signal.
 */
class DspPeakFollower : PeakFollower, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("PeakFollower.in")
    private val out = DspAudioOutput("PeakFollower.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    private var peak = 0f
    @Volatile private var decayCoeff = exp(ln(0.5f) / (0.1f * dspSampleRate))

    override fun setHalfLife(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f)
        decayCoeff = exp(ln(0.5f) / (s * dspSampleRate))
    }

    override fun getCurrent(): Double = peak.toDouble()

    override fun process(numFrames: Int) {
        val inp = inp.getBuffer()
        val o = out.getBuffer()
        var p = peak
        val coeff = decayCoeff
        for (i in 0 until numFrames) {
            val sample = abs(inp[i])
            p = max(sample, p * coeff)
            o[i] = p
        }
        peak = p
    }

    override fun allocateBuffers(maxFrames: Int) {
        this.inp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(this.inp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

/**
 * Soft limiter using tanh saturation with adjustable drive.
 */
class DspLimiter : Limiter, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("Limiter.in")
    private val drv = DspAudioInput("Limiter.drive", smoothed = true)
    private val out = DspAudioOutput("Limiter.out")

    override val input: AudioInput = inp
    override val drive: AudioInput = drv
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val i = inp.getBuffer()
        val d = drv.getBuffer()
        val o = out.getBuffer()
        for (j in 0 until numFrames) {
            o[j] = tanh(i[j] * d[j])
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inp.allocate(maxFrames)
        drv.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inp, drv)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

/**
 * Hard clipper: clamps output to +-1.0.
 * 100% transparent for in-range signals.
 */
class DspHardClip : HardClip, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("HardClip.in")
    private val out = DspAudioOutput("HardClip.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val i = inp.getBuffer()
        val o = out.getBuffer()
        for (j in 0 until numFrames) {
            o[j] = i[j].coerceIn(-1f, 1f)
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

/**
 * Circular buffer delay line with integer-sample delay.
 * Delay time is read per-sample from the delay input (in seconds).
 */
class DspDelayLine : DelayLine, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("DelayLine.in")
    private val del = DspAudioInput("DelayLine.delay", smoothed = true)
    private val out = DspAudioOutput("DelayLine.out")

    override val input: AudioInput = inp
    override val delay: AudioInput = del
    override val output: AudioOutput = out

    private var delayBuffer = FloatArray(0)
    private var writePos = 0
    private var bufferSize = 0

    override fun allocate(maxSamples: Int) {
        bufferSize = maxSamples
        delayBuffer = FloatArray(maxSamples)
        writePos = 0
    }

    override fun process(numFrames: Int) {
        if (bufferSize == 0) return
        val i = inp.getBuffer()
        val d = del.getBuffer()
        val o = out.getBuffer()
        val buf = delayBuffer
        val size = bufferSize
        var wp = writePos

        for (j in 0 until numFrames) {
            // Write current sample
            buf[wp] = i[j]

            // Calculate delay in samples from seconds
            val delaySamples = (d[j] * dspSampleRate).toInt().coerceIn(0, size - 1)

            // Read from circular buffer
            var readPos = wp - delaySamples
            if (readPos < 0) readPos += size
            o[j] = buf[readPos]

            wp++
            if (wp >= size) wp = 0
        }
        writePos = wp
    }

    override fun clear() {
        delayBuffer.fill(0f)
        writePos = 0
    }

    override fun allocateBuffers(maxFrames: Int) {
        inp.allocate(maxFrames)
        del.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inp, del)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
