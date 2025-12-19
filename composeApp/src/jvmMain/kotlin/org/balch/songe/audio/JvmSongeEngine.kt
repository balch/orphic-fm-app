package org.balch.songe.audio

import com.jsyn.JSyn
import com.jsyn.Synthesizer
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.SineOscillator

class JvmSongeEngine : SongeEngine {
    private val synth: Synthesizer = JSyn.createSynthesizer()
    private val lineOut: LineOut = LineOut()
    
    // Test Oscillator
    private val testOsc: SineOscillator = SineOscillator()

    override fun start() {
        synth.add(lineOut)
        synth.add(testOsc)
        
        // Connect osc to output
        testOsc.output.connect(0, lineOut.input, 0)
        testOsc.output.connect(0, lineOut.input, 1)
        
        // Initial silent amplitude
        testOsc.amplitude.set(0.0)
        
        lineOut.start()
        synth.start()
    }

    override fun stop() {
        synth.stop()
        lineOut.stop()
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        // TODO: Implement actual voice logic
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        // TODO: Implement actual voice logic
    }

    override fun setVoiceFeedback(index: Int, amount: Float) {
        // TODO: Implement actual voice logic
    }

    override fun setGroupPitch(groupIndex: Int, pitch: Float) {
        // TODO: Implement actual group logic
    }

    override fun setGroupFm(groupIndex: Int, amount: Float) {
        // TODO: Implement actual group logic
    }
    
    override fun setDrive(amount: Float) {
         // TODO: Implement global FX
    }

    override fun setDelay(time: Float, feedback: Float) {
         // TODO: Implement global FX
    }

    override fun playTestTone(frequency: Float) {
        testOsc.frequency.set(frequency.toDouble())
        testOsc.amplitude.set(0.5) // Half volume
    }

    override fun stopTestTone() {
        testOsc.amplitude.set(0.0)
    }
}
