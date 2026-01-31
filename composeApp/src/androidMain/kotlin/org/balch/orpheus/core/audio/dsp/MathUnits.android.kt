package org.balch.orpheus.core.audio.dsp

import com.jsyn.unitgen.Add as JsynAdd
import com.jsyn.unitgen.Maximum as JsynMaximum
import com.jsyn.unitgen.Minimum as JsynMinimum
import com.jsyn.unitgen.Multiply as JsynMultiply
import com.jsyn.unitgen.MultiplyAdd as JsynMultiplyAdd
import com.jsyn.unitgen.PassThrough as JsynPassThrough
import com.jsyn.unitgen.SineOscillator as JsynSineOsc
import com.jsyn.unitgen.SquareOscillator as JsynSquareOsc
import com.jsyn.unitgen.TriangleOscillator as JsynTriangleOsc

/**
 * Android actual implementations of math/utility units using JSyn.
 */



class JsynMultiplyWrapper : Multiply {
    internal val jsUnit = JsynMultiply()

    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}



class JsynAddWrapper : Add {
    internal val jsUnit = JsynAdd()

    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}



class JsynMultiplyAddWrapper : MultiplyAdd {
    internal val jsUnit = JsynMultiplyAdd()

    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val inputC: AudioInput = JsynAudioInput(jsUnit.inputC)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}



class JsynPassThroughWrapper : PassThrough {
    internal val jsUnit = JsynPassThrough()

    override val input: AudioInput = JsynAudioInput(jsUnit.input)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}



class JsynSineOscillatorWrapper : SineOscillator {
    internal val jsOsc = JsynSineOsc()

    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}



class JsynTriangleOscillatorWrapper : TriangleOscillator {
    internal val jsOsc = JsynTriangleOsc()

    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}



class JsynSquareOscillatorWrapper : SquareOscillator {
    internal val jsOsc = JsynSquareOsc()

    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}



class JsynMinimumWrapper : Minimum {
    internal val jsUnit = JsynMinimum()

    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}



class JsynMaximumWrapper : Maximum {
    internal val jsUnit = JsynMaximum()

    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}

class JsynSawtoothOscillatorWrapper : SawtoothOscillator {
    internal val jsOsc = com.jsyn.unitgen.SawtoothOscillator()

    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}
