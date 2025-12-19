package org.balch.songe.audio

import com.jsyn.JSyn
import com.jsyn.Synthesizer
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.SineOscillator

class JvmSongeEngine : SongeEngine {
    private val synth: Synthesizer = JSyn.createSynthesizer()
    private val lineOut: LineOut = LineOut()
    
    // 8 Voices
    private val voices = List(8) { SongeVoice() }
    
    // Mixer
    private val mixer = LineOut() // Using LineOut as a summing point for now or direct connect
    // Actually, let's use a proper Add unit chain or just connect all to LineOut input (JSyn ports sum automatically)

    override fun start() {
        synth.add(lineOut)
        
        // Add and connect voices
        voices.forEach { voice ->
            synth.add(voice)
            // Mix to Stereo L/R
            voice.outputPort.connect(0, lineOut.input, 0)
            voice.outputPort.connect(0, lineOut.input, 1)
        }
        
        lineOut.start()
        synth.start()
    }

    override fun stop() {
        synth.stop()
        lineOut.stop()
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        if (index in voices.indices) {
            // Map 0.0-1.0 to Frequency Range (e.g., 50Hz to 2000Hz)
            // Exponential mapping is better for pitch
            val minFreq = 50.0
            val maxFreq = 2000.0
            val freq = minFreq * Math.pow(maxFreq / minFreq, tune.toDouble())
            voices[index].frequency.set(freq)
        }
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        if (index in voices.indices) {
            voices[index].gate.set(if (active) 1.0 else 0.0)
        }
    }

    override fun setVoiceFeedback(index: Int, amount: Float) {
        // TODO: Implement Feedback routing later
    }

    override fun setGroupPitch(groupIndex: Int, pitch: Float) {
        // TODO: Implement Group Pitch Logic
    }

    override fun setGroupFm(groupIndex: Int, amount: Float) {
        // TODO: Implement FM Routing logic
    }
    
    override fun setDrive(amount: Float) {
         // TODO: Global VCA/Distortion
    }

    override fun setDelay(time: Float, feedback: Float) {
         // TODO: Delay Line
    }

    // Test tone is now just overriding Voice 0 for simplicity if needed, or we keep the separate osc
    private val testOsc = SineOscillator()
    override fun playTestTone(frequency: Float) {
        if (!synth.isRunning) start()
        synth.add(testOsc)
        testOsc.output.connect(0, lineOut.input, 0)
        testOsc.output.connect(0, lineOut.input, 1)
        testOsc.frequency.set(frequency.toDouble())
        testOsc.amplitude.set(0.5)
    }

    override fun stopTestTone() {
        // Disconnect test osc
        testOsc.amplitude.set(0.0)
        // ideally remove it, but setting amp to 0 is safe
    }
}
