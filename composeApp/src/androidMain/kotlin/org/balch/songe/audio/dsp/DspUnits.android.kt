package org.balch.songe.audio.dsp

/**
 * Android stub implementations of DSP units.
 * TODO: Replace with Oboe implementations.
 */

actual interface Oscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

actual interface Envelope : AudioUnit {
    actual val input: AudioInput
    actual fun setAttack(seconds: Double)
    actual fun setDecay(seconds: Double)
    actual fun setSustain(level: Double)
    actual fun setRelease(seconds: Double)
}

class StubEnvelope : Envelope {
    private val stubOutput = StubAudioOutput()
    override val input: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
    override fun setAttack(seconds: Double) {}
    override fun setDecay(seconds: Double) {}
    override fun setSustain(level: Double) {}
    override fun setRelease(seconds: Double) {}
}

actual interface DelayLine : AudioUnit {
    actual val input: AudioInput
    actual val delay: AudioInput
    actual fun allocate(maxSamples: Int)
}

class StubDelayLine : DelayLine {
    private val stubOutput = StubAudioOutput()
    override val input: AudioInput = StubAudioInput()
    override val delay: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
    override fun allocate(maxSamples: Int) {}
}

actual interface PeakFollower : AudioUnit {
    actual val input: AudioInput
    actual fun setHalfLife(seconds: Double)
}

class StubPeakFollower : PeakFollower {
    private val stubOutput = StubAudioOutput()
    override val input: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
    override fun setHalfLife(seconds: Double) {}
}

actual interface Limiter : AudioUnit {
    actual val input: AudioInput
    actual val drive: AudioInput
}

class StubLimiter : Limiter {
    private val stubOutput = StubAudioOutput()
    override val input: AudioInput = StubAudioInput()
    override val drive: AudioInput = StubAudioInput()
    override val output: AudioOutput = stubOutput
}
