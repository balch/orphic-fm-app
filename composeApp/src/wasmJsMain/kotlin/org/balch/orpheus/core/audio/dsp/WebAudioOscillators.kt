package org.balch.orpheus.core.audio.dsp

class WebAudioSineOscillator(private val context: AudioContext) : SineOscillator {
    private val osc = context.createOscillator().also {
        it.type = "sine"
        it.start()
    }
    // For output gain
    private val outputGain = context.createGain()
    init { osc.connect(outputGain) }

    override val frequency: AudioInput = WebAudioParamInput(osc.frequency, context)
    // Amp input controls the output gain
    override val amplitude: AudioInput = WebAudioParamInput(outputGain.gain, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
}

class WebAudioTriangleOscillator(private val context: AudioContext) : TriangleOscillator {
    private val osc = context.createOscillator().also {
        it.type = "triangle"
        it.start()
    }
    private val outputGain = context.createGain()
    init { osc.connect(outputGain) }
    
    override val frequency: AudioInput = WebAudioParamInput(osc.frequency, context)
    override val amplitude: AudioInput = WebAudioParamInput(outputGain.gain, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
}

class WebAudioSquareOscillator(private val context: AudioContext) : SquareOscillator {
    private val osc = context.createOscillator().also {
        it.type = "square"
        it.start()
    }
    private val outputGain = context.createGain()
    init { osc.connect(outputGain) }
    
    override val frequency: AudioInput = WebAudioParamInput(osc.frequency, context)
    override val amplitude: AudioInput = WebAudioParamInput(outputGain.gain, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
}

class WebAudioSawtoothOscillator(private val context: AudioContext) : SawtoothOscillator {
    private val osc = context.createOscillator().also {
        it.type = "sawtooth"
        it.start()
    }
    private val outputGain = context.createGain()
    init { osc.connect(outputGain) }

    override val frequency: AudioInput = WebAudioParamInput(osc.frequency, context)
    override val amplitude: AudioInput = WebAudioParamInput(outputGain.gain, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
}
