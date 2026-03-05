package org.balch.orpheus.core.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh

/**
 * Oboe stream sample rate used by all Kotlin-native DSP units.
 * Assumed 48kHz — OboeAudioEngine.start() logs a warning if the actual
 * stream rate differs. Most Android devices support 48kHz natively.
 */
const val DSP_SAMPLE_RATE = 48000f

/**
 * Kotlin-native DSP processing units for the Oboe audio backend.
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
class OboeEnvelope : Envelope, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("Envelope.gate")
    private val out = OboeAudioOutput("Envelope.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    private var state = ENV_IDLE
    private var level = 0f
    private var gateWasOn = false

    // Coefficients — written from UI thread, read from audio thread
    @Volatile private var attackRate = 1f / (0.01f * DSP_SAMPLE_RATE)
    @Volatile private var decayCoeff = exp(-1f / (0.1f * DSP_SAMPLE_RATE))
    @Volatile private var sustainLevel = 0.5f
    @Volatile private var releaseCoeff = exp(-1f / (0.1f * DSP_SAMPLE_RATE))

    override fun setAttack(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f) // minimum 1ms to avoid division by zero
        attackRate = 1f / (s * DSP_SAMPLE_RATE)
    }

    override fun setDecay(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f)
        decayCoeff = exp(-1f / (s * DSP_SAMPLE_RATE))
    }

    override fun setSustain(level: Double) {
        sustainLevel = level.toFloat().coerceIn(0f, 1f)
    }

    override fun setRelease(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f)
        releaseCoeff = exp(-1f / (s * DSP_SAMPLE_RATE))
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
class OboeLinearRamp : LinearRamp, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("LinearRamp.target")
    private val tim = OboeAudioInput("LinearRamp.time", smoothed = true)
    private val out = OboeAudioOutput("LinearRamp.out")

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
            val rate = 1f / (rampTime * DSP_SAMPLE_RATE)
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
class OboePeakFollower : PeakFollower, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("PeakFollower.in")
    private val out = OboeAudioOutput("PeakFollower.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    private var peak = 0f
    @Volatile private var decayCoeff = exp(ln(0.5f) / (0.1f * DSP_SAMPLE_RATE))

    override fun setHalfLife(seconds: Double) {
        val s = max(seconds.toFloat(), 0.001f)
        decayCoeff = exp(ln(0.5f) / (s * DSP_SAMPLE_RATE))
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
class OboeLimiter : Limiter, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("Limiter.in")
    private val drv = OboeAudioInput("Limiter.drive", smoothed = true)
    private val out = OboeAudioOutput("Limiter.out")

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
 * Hard clipper: clamps output to ±1.0.
 * 100% transparent for in-range signals.
 */
class OboeHardClip : HardClip, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("HardClip.in")
    private val out = OboeAudioOutput("HardClip.out")

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
class OboeDelayLine : DelayLine, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("DelayLine.in")
    private val del = OboeAudioInput("DelayLine.delay", smoothed = true)
    private val out = OboeAudioOutput("DelayLine.out")

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
            val delaySamples = (d[j] * DSP_SAMPLE_RATE).toInt().coerceIn(0, size - 1)

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
