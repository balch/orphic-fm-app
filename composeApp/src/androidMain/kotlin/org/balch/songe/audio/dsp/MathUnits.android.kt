package org.balch.songe.audio.dsp

/**
 * Android stub implementations of math/utility units.
 * TODO: Replace with Oboe implementations.
 */

actual interface Multiply : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

class StubMultiply : Multiply {
    private val stubOutput = StubAudioOutput()
    override val inputA: AudioInput = StubAudioInput()
    override val inputB: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}

actual interface Add : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

class StubAdd : Add {
    private val stubOutput = StubAudioOutput()
    override val inputA: AudioInput = StubAudioInput()
    override val inputB: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}

actual interface MultiplyAdd : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
    actual val inputC: AudioInput
}

class StubMultiplyAdd : MultiplyAdd {
    private val stubOutput = StubAudioOutput()
    override val inputA: AudioInput = StubAudioInput()
    override val inputB: AudioInput = StubAudioInput()
    override val inputC: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}

actual interface PassThrough : AudioUnit {
    actual val input: AudioInput
}

class StubPassThrough : PassThrough {
    private val stubOutput = StubAudioOutput()
    override val input: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}

actual interface SineOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class StubSineOscillator : SineOscillator {
    private val stubOutput = StubAudioOutput()
    override val frequency: AudioInput = StubAudioInput()
    override val amplitude: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}

actual interface TriangleOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class StubTriangleOscillator : TriangleOscillator {
    private val stubOutput = StubAudioOutput()
    override val frequency: AudioInput = StubAudioInput()
    override val amplitude: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}

actual interface SquareOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class StubSquareOscillator : SquareOscillator {
    private val stubOutput = StubAudioOutput()
    override val frequency: AudioInput = StubAudioInput()
    override val amplitude: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}
