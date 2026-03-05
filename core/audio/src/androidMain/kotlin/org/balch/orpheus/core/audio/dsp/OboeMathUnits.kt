package org.balch.orpheus.core.audio.dsp

import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin-native math DSP units for the Oboe audio backend.
 * Zero-allocation process() methods for real-time audio thread safety.
 */

class OboeMultiply : Multiply, OboeProcessable {
    @Volatile override var enabled = true
    private val inA = OboeAudioInput("Multiply.A", smoothed = true)
    private val inB = OboeAudioInput("Multiply.B", smoothed = true)
    private val out = OboeAudioOutput("Multiply.out")

    override val inputA: AudioInput = inA
    override val inputB: AudioInput = inB
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val a = inA.getBuffer()
        val b = inB.getBuffer()
        val o = out.getBuffer()
        for (i in 0 until numFrames) {
            o[i] = a[i] * b[i]
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inA.allocate(maxFrames)
        inB.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inA, inB)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboeAdd : Add, OboeProcessable {
    @Volatile override var enabled = true
    private val inA = OboeAudioInput("Add.A", smoothed = true)
    private val inB = OboeAudioInput("Add.B", smoothed = true)
    private val out = OboeAudioOutput("Add.out")

    override val inputA: AudioInput = inA
    override val inputB: AudioInput = inB
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val a = inA.getBuffer()
        val b = inB.getBuffer()
        val o = out.getBuffer()
        for (i in 0 until numFrames) {
            o[i] = a[i] + b[i]
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inA.allocate(maxFrames)
        inB.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inA, inB)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboeMultiplyAdd : MultiplyAdd, OboeProcessable {
    @Volatile override var enabled = true
    private val inA = OboeAudioInput("MultiplyAdd.A", smoothed = true)
    private val inB = OboeAudioInput("MultiplyAdd.B", smoothed = true)
    private val inC = OboeAudioInput("MultiplyAdd.C", smoothed = true)
    private val out = OboeAudioOutput("MultiplyAdd.out")

    override val inputA: AudioInput = inA
    override val inputB: AudioInput = inB
    override val inputC: AudioInput = inC
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val a = inA.getBuffer()
        val b = inB.getBuffer()
        val c = inC.getBuffer()
        val o = out.getBuffer()
        for (i in 0 until numFrames) {
            o[i] = a[i] * b[i] + c[i]
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inA.allocate(maxFrames)
        inB.allocate(maxFrames)
        inC.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inA, inB, inC)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboePassThroughUnit : PassThrough, OboeProcessable {
    @Volatile override var enabled = true
    private val inp = OboeAudioInput("PassThrough.in")
    private val out = OboeAudioOutput("PassThrough.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    private var lastValue = 0.0

    override fun getInstantaneousValue(): Double = lastValue

    override fun process(numFrames: Int) {
        val i = inp.getBuffer()
        val o = out.getBuffer()
        System.arraycopy(i, 0, o, 0, numFrames)
        if (numFrames > 0) lastValue = o[numFrames - 1].toDouble()
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

class OboeMinimum : Minimum, OboeProcessable {
    @Volatile override var enabled = true
    private val inA = OboeAudioInput("Minimum.A", smoothed = true)
    private val inB = OboeAudioInput("Minimum.B", smoothed = true)
    private val out = OboeAudioOutput("Minimum.out")

    override val inputA: AudioInput = inA
    override val inputB: AudioInput = inB
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val a = inA.getBuffer()
        val b = inB.getBuffer()
        val o = out.getBuffer()
        for (i in 0 until numFrames) {
            o[i] = min(a[i], b[i])
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inA.allocate(maxFrames)
        inB.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inA, inB)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}

class OboeMaximum : Maximum, OboeProcessable {
    @Volatile override var enabled = true
    private val inA = OboeAudioInput("Maximum.A", smoothed = true)
    private val inB = OboeAudioInput("Maximum.B", smoothed = true)
    private val out = OboeAudioOutput("Maximum.out")

    override val inputA: AudioInput = inA
    override val inputB: AudioInput = inB
    override val output: AudioOutput = out

    override fun process(numFrames: Int) {
        val a = inA.getBuffer()
        val b = inB.getBuffer()
        val o = out.getBuffer()
        for (i in 0 until numFrames) {
            o[i] = max(a[i], b[i])
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        inA.allocate(maxFrames)
        inB.allocate(maxFrames)
        out.allocate(maxFrames)
    }

    private val inputPorts = listOf(inA, inB)
    private val outputPorts = listOf(out)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
