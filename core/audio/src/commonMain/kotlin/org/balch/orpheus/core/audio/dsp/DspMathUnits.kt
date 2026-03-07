package org.balch.orpheus.core.audio.dsp

import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin-native math DSP units.
 * Zero-allocation process() methods for real-time audio thread safety.
 */

class DspMultiply : Multiply, DspProcessable {
    @Volatile override var enabled = true
    private val inA = DspAudioInput("Multiply.A", smoothed = true)
    private val inB = DspAudioInput("Multiply.B", smoothed = true)
    private val out = DspAudioOutput("Multiply.out")

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

class DspAdd : Add, DspProcessable {
    @Volatile override var enabled = true
    private val inA = DspAudioInput("Add.A", smoothed = true)
    private val inB = DspAudioInput("Add.B", smoothed = true)
    private val out = DspAudioOutput("Add.out")

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

class DspMultiplyAdd : MultiplyAdd, DspProcessable {
    @Volatile override var enabled = true
    private val inA = DspAudioInput("MultiplyAdd.A", smoothed = true)
    private val inB = DspAudioInput("MultiplyAdd.B", smoothed = true)
    private val inC = DspAudioInput("MultiplyAdd.C", smoothed = true)
    private val out = DspAudioOutput("MultiplyAdd.out")

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

class DspPassThrough : PassThrough, DspProcessable {
    @Volatile override var enabled = true
    private val inp = DspAudioInput("PassThrough.in")
    private val out = DspAudioOutput("PassThrough.out")

    override val input: AudioInput = inp
    override val output: AudioOutput = out

    private var lastValue = 0.0

    override fun getInstantaneousValue(): Double = lastValue

    override fun process(numFrames: Int) {
        val i = inp.getBuffer()
        val o = out.getBuffer()
        i.copyInto(o, 0, 0, numFrames)
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

class DspMinimum : Minimum, DspProcessable {
    @Volatile override var enabled = true
    private val inA = DspAudioInput("Minimum.A", smoothed = true)
    private val inB = DspAudioInput("Minimum.B", smoothed = true)
    private val out = DspAudioOutput("Minimum.out")

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

class DspMaximum : Maximum, DspProcessable {
    @Volatile override var enabled = true
    private val inA = DspAudioInput("Maximum.A", smoothed = true)
    private val inB = DspAudioInput("Maximum.B", smoothed = true)
    private val out = DspAudioOutput("Maximum.out")

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
