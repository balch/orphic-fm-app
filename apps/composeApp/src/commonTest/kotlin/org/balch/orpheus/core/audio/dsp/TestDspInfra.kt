package org.balch.orpheus.core.audio.dsp

/**
 * Minimal test stubs for AudioEngine and DspFactory.
 * Just enough to construct GlobalTempo in tests.
 */
class TestAudioInput : AudioInput {
    override fun set(value: Double) {}
    override fun disconnectAll() {}
}

class TestAudioOutput : AudioOutput {
    override fun connect(input: AudioInput) {}
    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {}
}

class TestClockUnit : ClockUnit {
    override val frequency: AudioInput = TestAudioInput()
    override val pulseWidth: AudioInput = TestAudioInput()
    override val output: AudioOutput = TestAudioOutput()
}

class TestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun addUnit(unit: AudioUnit) {}
    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {}
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
    override val lineOutLeft: AudioInput = TestAudioInput()
    override val lineOutRight: AudioInput = TestAudioInput()
}

class TestDspFactory : DspFactory {
    override fun createSineOscillator(): SineOscillator = throw NotImplementedError()
    override fun createTriangleOscillator(): TriangleOscillator = throw NotImplementedError()
    override fun createSquareOscillator(): SquareOscillator = throw NotImplementedError()
    override fun createSawtoothOscillator(): SawtoothOscillator = throw NotImplementedError()
    override fun createEnvelope(): Envelope = throw NotImplementedError()
    override fun createDelayLine(): DelayLine = throw NotImplementedError()
    override fun createPeakFollower(): PeakFollower = throw NotImplementedError()
    override fun createLimiter(): Limiter = throw NotImplementedError()
    override fun createMultiply(): Multiply = throw NotImplementedError()
    override fun createAdd(): Add = throw NotImplementedError()
    override fun createMultiplyAdd(): MultiplyAdd = throw NotImplementedError()
    override fun createPassThrough(): PassThrough = throw NotImplementedError()
    override fun createMinimum(): Minimum = throw NotImplementedError()
    override fun createMaximum(): Maximum = throw NotImplementedError()
    override fun createLinearRamp(): LinearRamp = throw NotImplementedError()
    override fun createAutomationPlayer(): AutomationPlayer = throw NotImplementedError()
    override fun createPlaitsUnit(): PlaitsUnit = throw NotImplementedError()
    override fun createDrumUnit(): DrumUnit = throw NotImplementedError()
    override fun createResonatorUnit(): ResonatorUnit = throw NotImplementedError()
    override fun createGrainsUnit(): GrainsUnit = throw NotImplementedError()
    override fun createLooperUnit(): LooperUnit = throw NotImplementedError()
    override fun createWarpsUnit(): WarpsUnit = throw NotImplementedError()
    override fun createClockUnit(): ClockUnit = TestClockUnit()
    override fun createFluxUnit(): FluxUnit = throw NotImplementedError()
    override fun createReverbUnit(): ReverbUnit = throw NotImplementedError()
    override fun createTtsPlayerUnit(): TtsPlayerUnit = throw NotImplementedError()
    override fun createSpeechEffectsUnit(): SpeechEffectsUnit = throw NotImplementedError()
}
