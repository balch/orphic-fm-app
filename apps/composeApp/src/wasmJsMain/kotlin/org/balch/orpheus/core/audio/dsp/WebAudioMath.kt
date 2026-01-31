package org.balch.orpheus.core.audio.dsp

class WebAudioMultiply(private val context: AudioContext) : Multiply {
    private val gain = context.createGain()
    // A -> Gain.gain (AudioParam)
    // B -> Gain.input (Node)
    // Output = A * B
    init { gain.gain.value = 0f }
    
    override val inputA: AudioInput = WebAudioParamInput(gain.gain, context)
    override val inputB: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(gain)
}

class WebAudioAdd(private val context: AudioContext) : Add {
    private val gain = context.createGain() // Summing node with gain 1
    init { gain.gain.value = 1f }
    
    override val inputA: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val inputB: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(gain)
}

class WebAudioMultiplyAdd(private val context: AudioContext) : MultiplyAdd {
    // (A * B) + C
    private val mulNode = context.createGain()
    private val sumNode = context.createGain()
    
    init {
        mulNode.gain.value = 0f
        mulNode.connect(sumNode)
        sumNode.gain.value = 1f
    }
    
    override val inputA: AudioInput = WebAudioParamInput(mulNode.gain, context)
    override val inputB: AudioInput = WebAudioNodeInput(mulNode, 0, context)
    override val inputC: AudioInput = WebAudioNodeInput(sumNode, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(sumNode)
}

class WebAudioPassThrough(private val context: AudioContext) : PassThrough {
    private val gain = context.createGain()
    init { gain.gain.value = 1f }
    
    override val input: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(gain)
}

class WebAudioMinimum(private val context: AudioContext) : Minimum {
    // Stub
    private val gain = context.createGain()
    override val inputA: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val inputB: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(gain)
}

class WebAudioMaximum(private val context: AudioContext) : Maximum {
    // Stub
    private val gain = context.createGain()
    override val inputA: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val inputB: AudioInput = WebAudioNodeInput(gain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(gain)
}
