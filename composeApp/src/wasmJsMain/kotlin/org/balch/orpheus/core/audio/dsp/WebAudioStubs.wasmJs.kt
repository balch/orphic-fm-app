package org.balch.orpheus.core.audio.dsp

class StubAudioInput : AudioInput {
    override fun set(value: Double) {}
    override fun disconnectAll() {}
}

class StubAudioOutput : AudioOutput {
    override fun connect(input: AudioInput) {}
    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {}
}

// ... existing Stubs ...

class StubDrumUnit : DrumUnit {
    override val triggerInputBd = StubAudioInput()
    override val triggerInputSd = StubAudioInput()
    override val triggerInputHh = StubAudioInput()
    override val output = StubAudioOutput()
    override fun trigger(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, param4: Float, param5: Float) {}
    override fun setParameters(type: Int, frequency: Float, tone: Float, decay: Float, param4: Float, param5: Float) {}
    override fun trigger(type: Int, accent: Float) {}
}

class StubResonatorUnit : ResonatorUnit {
    override val input = StubAudioInput()
    override val auxOutput = StubAudioOutput()
    override val output = StubAudioOutput()
    override fun setEnabled(enabled: Boolean) {}
    override fun setMode(mode: Int) {}
    override fun setStructure(value: Float) {}
    override fun setBrightness(value: Float) {}
    override fun setDamping(value: Float) {}
    override fun setPosition(value: Float) {}
    override fun strum(frequency: Float) {}
}

class StubGrainsUnit : GrainsUnit {
    override val inputLeft = StubAudioInput()
    override val inputRight = StubAudioInput()
    override val outputRight = StubAudioOutput()
    override val output = StubAudioOutput()
    override val position = StubAudioInput()
    override val size = StubAudioInput()
    override val pitch = StubAudioInput()
    override val density = StubAudioInput()
    override val texture = StubAudioInput()
    override val dryWet = StubAudioInput()
    override val freeze = StubAudioInput()
    override val trigger = StubAudioInput()
    override fun setMode(mode: Int) {}
}

class StubLooperUnit : LooperUnit {
    override val inputLeft = StubAudioInput()
    override val inputRight = StubAudioInput()
    override val output = StubAudioOutput()
    override val outputRight = StubAudioOutput()
    override val recordGate = StubAudioInput()
    override val playGate = StubAudioInput()
    override fun setRecording(active: Boolean) {}
    override fun setPlaying(active: Boolean) {}
    override fun allocate(maxSeconds: Double) {}
    override fun clear() {}
    override fun getPosition(): Float = 0f
    override fun getLoopDuration(): Double = 0.0
}

class StubWarpsUnit : WarpsUnit {
    override val inputLeft = StubAudioInput()
    override val inputRight = StubAudioInput()
    override val outputRight = StubAudioOutput()
    override val output = StubAudioOutput()
    override val algorithm = StubAudioInput()
    override val timbre = StubAudioInput()
    override val level1 = StubAudioInput()
    override val level2 = StubAudioInput()
}

class StubClockUnit : ClockUnit {
    override val frequency = StubAudioInput()
    override val pulseWidth = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubFluxUnit : FluxUnit {
    override val clock = StubAudioInput()
    override val spread = StubAudioInput()
    override val bias = StubAudioInput()
    override val steps = StubAudioInput()
    override val dejaVu = StubAudioInput()
    override val length = StubAudioInput()
    override val rate = StubAudioInput()
    override val jitter = StubAudioInput()
    override val probability = StubAudioInput()
    override val gateLength = StubAudioInput()
    override val output = StubAudioOutput()
    override val outputX1 = StubAudioOutput()
    override val outputX3 = StubAudioOutput()
    override val outputT2 = StubAudioOutput()
    override val outputT1 = StubAudioOutput()
    override val outputT3 = StubAudioOutput()
    override fun setScale(index: Int) {}
}

class StubEnvelope : Envelope {
    override val input = StubAudioInput()
    override val output = StubAudioOutput()
    override fun setAttack(seconds: Double) {}
    override fun setDecay(seconds: Double) {}
    override fun setSustain(level: Double) {}
    override fun setRelease(seconds: Double) {}
}

class StubDelayLine : DelayLine {
    override val input = StubAudioInput()
    override val delay = StubAudioInput()
    override val output = StubAudioOutput()
    override fun allocate(maxSamples: Int) {}
}

class StubPeakFollower : PeakFollower {
    override val input = StubAudioInput()
    override val output = StubAudioOutput()
    override fun setHalfLife(seconds: Double) {}
    override fun getCurrent(): Double = 0.0
}

class StubLimiter : Limiter {
    override val input = StubAudioInput()
    override val drive = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubLinearRamp : LinearRamp {
    override val input = StubAudioInput()
    override val time = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubAutomationPlayer : AutomationPlayer {
    override val output = StubAudioOutput()
    override fun setPath(times: FloatArray, values: FloatArray, count: Int) {}
    override fun setDuration(seconds: Float) {}
    override fun setMode(mode: Int) {}
    override fun play() {}
    override fun stop() {}
    override fun reset() {}
}

class StubSawtoothOscillator : SawtoothOscillator {
    override val frequency = StubAudioInput()
    override val amplitude = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubSineOscillator : SineOscillator {
    override val frequency = StubAudioInput()
    override val amplitude = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubTriangleOscillator : TriangleOscillator {
    override val frequency = StubAudioInput()
    override val amplitude = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubSquareOscillator : SquareOscillator {
    override val frequency = StubAudioInput()
    override val amplitude = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubMultiply : Multiply {
    override val inputA = StubAudioInput()
    override val inputB = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubAdd : Add {
    override val inputA = StubAudioInput()
    override val inputB = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubMultiplyAdd : MultiplyAdd {
    override val inputA = StubAudioInput()
    override val inputB = StubAudioInput()
    override val inputC = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubPassThrough : PassThrough {
    override val input = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubMinimum : Minimum {
    override val inputA = StubAudioInput()
    override val inputB = StubAudioInput()
    override val output = StubAudioOutput()
}

class StubMaximum : Maximum {
    override val inputA = StubAudioInput()
    override val inputB = StubAudioInput()
    override val output = StubAudioOutput()
}
