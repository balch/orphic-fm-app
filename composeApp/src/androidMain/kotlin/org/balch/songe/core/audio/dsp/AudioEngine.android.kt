package org.balch.songe.core.audio.dsp

/**
 * Android stub implementation of AudioEngine.
 * TODO: Replace with Oboe-based implementation.
 */
actual class AudioEngine actual constructor() {
    actual fun start() {}
    actual fun stop() {}

    actual val isRunning: Boolean = false
    actual val sampleRate: Int = 44100

    actual fun addUnit(unit: AudioUnit) {}

    actual fun createSineOscillator(): SineOscillator = StubSineOscillator()
    actual fun createTriangleOscillator(): TriangleOscillator = StubTriangleOscillator()
    actual fun createSquareOscillator(): SquareOscillator = StubSquareOscillator()
    actual fun createEnvelope(): Envelope = StubEnvelope()
    actual fun createDelayLine(): DelayLine = StubDelayLine()
    actual fun createPeakFollower(): PeakFollower = StubPeakFollower()
    actual fun createLimiter(): Limiter = StubLimiter()
    actual fun createMultiply(): Multiply = StubMultiply()
    actual fun createAdd(): Add = StubAdd()
    actual fun createMultiplyAdd(): MultiplyAdd = StubMultiplyAdd()
    actual fun createPassThrough(): PassThrough = StubPassThrough()
    actual fun createMinimum(): Minimum = StubMinimum()
    actual fun createMaximum(): Maximum = StubMaximum()

    actual val lineOutLeft: AudioInput = StubAudioInput()
    actual val lineOutRight: AudioInput = StubAudioInput()

    actual fun getCpuLoad(): Float = 0f
}
