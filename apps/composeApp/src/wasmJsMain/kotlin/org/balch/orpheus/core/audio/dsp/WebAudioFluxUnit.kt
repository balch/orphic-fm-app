package org.balch.orpheus.core.audio.dsp

class WebAudioFluxUnit(private val context: AudioContext) : FluxUnit {
    private val outputGain = context.createGain()
    
    // Stub outputs
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val outputX1: AudioOutput = WebAudioNodeOutput(context.createGain())
    override val outputX3: AudioOutput = WebAudioNodeOutput(context.createGain())
    override val outputT2: AudioOutput = WebAudioNodeOutput(context.createGain())
    override val outputT1: AudioOutput = WebAudioNodeOutput(context.createGain())
    override val outputT3: AudioOutput = WebAudioNodeOutput(context.createGain())
    
    // Stub inputs
    override val clock: AudioInput = WebAudioManualInput(context) {}
    override val spread: AudioInput = WebAudioManualInput(context) {}
    override val bias: AudioInput = WebAudioManualInput(context) {}
    override val steps: AudioInput = WebAudioManualInput(context) {}
    override val dejaVu: AudioInput = WebAudioManualInput(context) {}
    override val length: AudioInput = WebAudioManualInput(context) {}
    override val rate: AudioInput = WebAudioManualInput(context) {}
    override val jitter: AudioInput = WebAudioManualInput(context) {}
    override val probability: AudioInput = WebAudioManualInput(context) {}
    override val pulseWidth: AudioInput = WebAudioManualInput(context) {}
    
    override fun setScale(index: Int) {}
}
