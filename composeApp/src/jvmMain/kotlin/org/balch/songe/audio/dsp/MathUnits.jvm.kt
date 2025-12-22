package org.balch.songe.audio.dsp

import org.balch.songe.core.audio.dsp.Add
import org.balch.songe.core.audio.dsp.AudioInput
import org.balch.songe.core.audio.dsp.AudioOutput
import org.balch.songe.core.audio.dsp.AudioUnit
import org.balch.songe.core.audio.dsp.Maximum
import org.balch.songe.core.audio.dsp.Minimum
import org.balch.songe.core.audio.dsp.Multiply
import org.balch.songe.core.audio.dsp.MultiplyAdd
import org.balch.songe.core.audio.dsp.PassThrough
import org.balch.songe.core.audio.dsp.SineOscillator
import org.balch.songe.core.audio.dsp.SquareOscillator
import org.balch.songe.core.audio.dsp.TriangleOscillator
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
 * JVM actual implementations of math/utility units.
 */

actual interface Multiply : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

class JsynMultiplyWrapper : Multiply {
    internal val jsUnit = JsynMultiply()
    
    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}

actual interface Add : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

class JsynAddWrapper : Add {
    internal val jsUnit = JsynAdd()
    
    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}

actual interface MultiplyAdd : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
    actual val inputC: AudioInput
}

class JsynMultiplyAddWrapper : MultiplyAdd {
    internal val jsUnit = JsynMultiplyAdd()
    
    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val inputC: AudioInput = JsynAudioInput(jsUnit.inputC)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}

actual interface PassThrough : AudioUnit {
    actual val input: AudioInput
}

class JsynPassThroughWrapper : PassThrough {
    internal val jsUnit = JsynPassThrough()
    
    override val input: AudioInput = JsynAudioInput(jsUnit.input)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}

actual interface SineOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class JsynSineOscillatorWrapper : SineOscillator {
    internal val jsOsc = JsynSineOsc()
    
    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}

actual interface TriangleOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class JsynTriangleOscillatorWrapper : TriangleOscillator {
    internal val jsOsc = JsynTriangleOsc()
    
    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}

actual interface SquareOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class JsynSquareOscillatorWrapper : SquareOscillator {
    internal val jsOsc = JsynSquareOsc()
    
    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val amplitude: AudioInput = JsynAudioInput(jsOsc.amplitude)
    override val output: AudioOutput = JsynAudioOutput(jsOsc.output)
}

actual interface Minimum : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

class JsynMinimumWrapper : Minimum {
    internal val jsUnit = JsynMinimum()
    
    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}

actual interface Maximum : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

class JsynMaximumWrapper : Maximum {
    internal val jsUnit = JsynMaximum()
    
    override val inputA: AudioInput = JsynAudioInput(jsUnit.inputA)
    override val inputB: AudioInput = JsynAudioInput(jsUnit.inputB)
    override val output: AudioOutput = JsynAudioOutput(jsUnit.output)
}
