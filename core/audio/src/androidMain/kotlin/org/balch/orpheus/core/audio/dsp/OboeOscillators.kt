package org.balch.orpheus.core.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * Kotlin-native oscillator DSP units for the Oboe audio backend.
 * Phase-accumulator pattern with per-sample frequency/amplitude modulation.
 * Zero-allocation process() methods for real-time audio thread safety.
 */

class OboeSineOscillator : SineOscillator, OboeProcessable {
    @Volatile override var enabled = true
    private val freq = OboeAudioInput("SineOsc.freq", smoothed = true)
    private val amp = OboeAudioInput("SineOsc.amp", smoothed = true)
    private val out = OboeAudioOutput("SineOsc.out")

    override val frequency: AudioInput = freq
    override val amplitude: AudioInput = amp
    override val output: AudioOutput = out

    private var phase = 0f

    override fun process(numFrames: Int) {
        val f = freq.getBuffer()
        val a = amp.getBuffer()
        val o = out.getBuffer()
        var p = phase
        for (i in 0 until numFrames) {
            o[i] = sin(p) * a[i]
            p += f[i] * TWO_PI / DSP_SAMPLE_RATE
            if (p >= TWO_PI) p -= TWO_PI
            if (p < 0f) p += TWO_PI
        }
        phase = p
    }

    override fun allocateBuffers(maxFrames: Int) {
        freq.allocate(maxFrames)
        amp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(freq, amp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboeTriangleOscillator : TriangleOscillator, OboeProcessable {
    @Volatile override var enabled = true
    private val freq = OboeAudioInput("TriOsc.freq", smoothed = true)
    private val amp = OboeAudioInput("TriOsc.amp", smoothed = true)
    private val out = OboeAudioOutput("TriOsc.out")

    override val frequency: AudioInput = freq
    override val amplitude: AudioInput = amp
    override val output: AudioOutput = out

    private var phase = 0f // 0..1 range

    override fun process(numFrames: Int) {
        val f = freq.getBuffer()
        val a = amp.getBuffer()
        val o = out.getBuffer()
        var p = phase
        for (i in 0 until numFrames) {
            // Triangle: rises from -1 to 1 in first half, falls from 1 to -1 in second half
            o[i] = (4f * abs(p - 0.5f) - 1f) * a[i]
            p += f[i] / DSP_SAMPLE_RATE
            p -= floor(p)
        }
        phase = p
    }

    override fun allocateBuffers(maxFrames: Int) {
        freq.allocate(maxFrames)
        amp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(freq, amp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboeSquareOscillator : SquareOscillator, OboeProcessable {
    @Volatile override var enabled = true
    private val freq = OboeAudioInput("SqOsc.freq", smoothed = true)
    private val amp = OboeAudioInput("SqOsc.amp", smoothed = true)
    private val out = OboeAudioOutput("SqOsc.out")

    override val frequency: AudioInput = freq
    override val amplitude: AudioInput = amp
    override val output: AudioOutput = out

    private var phase = 0f // 0..1 range

    override fun process(numFrames: Int) {
        val f = freq.getBuffer()
        val a = amp.getBuffer()
        val o = out.getBuffer()
        var p = phase
        for (i in 0 until numFrames) {
            o[i] = (if (p < 0.5f) 1f else -1f) * a[i]
            p += f[i] / DSP_SAMPLE_RATE
            p -= floor(p)
        }
        phase = p
    }

    override fun allocateBuffers(maxFrames: Int) {
        freq.allocate(maxFrames)
        amp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(freq, amp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboeSawtoothOscillator : SawtoothOscillator, OboeProcessable {
    @Volatile override var enabled = true
    private val freq = OboeAudioInput("SawOsc.freq", smoothed = true)
    private val amp = OboeAudioInput("SawOsc.amp", smoothed = true)
    private val out = OboeAudioOutput("SawOsc.out")

    override val frequency: AudioInput = freq
    override val amplitude: AudioInput = amp
    override val output: AudioOutput = out

    private var phase = 0f // 0..1 range

    override fun process(numFrames: Int) {
        val f = freq.getBuffer()
        val a = amp.getBuffer()
        val o = out.getBuffer()
        var p = phase
        for (i in 0 until numFrames) {
            o[i] = (2f * p - 1f) * a[i]
            p += f[i] / DSP_SAMPLE_RATE
            p -= floor(p)
        }
        phase = p
    }

    override fun allocateBuffers(maxFrames: Int) {
        freq.allocate(maxFrames)
        amp.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(freq, amp)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
