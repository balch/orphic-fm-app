package org.balch.orpheus.core.audio.dsp

class JsynSawtoothOscillatorWrapper : SawtoothOscillator {
    internal val jsOsc = com.jsyn.unitgen.SawtoothOscillator()

    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}
