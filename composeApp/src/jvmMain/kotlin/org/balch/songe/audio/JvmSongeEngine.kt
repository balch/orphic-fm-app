package org.balch.songe.audio

import com.jsyn.JSyn
import com.jsyn.Synthesizer
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.SineOscillator
import org.balch.songe.util.Logger

class JvmSongeEngine : SongeEngine {
    private val synth: Synthesizer = JSyn.createSynthesizer()
    private val lineOut: LineOut = LineOut()
    
    // 8 Voices
    private val voices = List(8) { SongeVoice() }
    
    // Mixer
    private val mixer = LineOut() // Using LineOut as a summing point for now or direct connect
    // Actually, let's use a proper Add unit chain or just connect all to LineOut input (JSyn ports sum automatically)

    // Monitoring
    private val peakFollower = com.jsyn.unitgen.PeakFollower()
    
    init {
        // Monitor Logic: PeakFollower sums inputs automatically
        synth.add(peakFollower)
        peakFollower.halfLife.set(0.1)
    }

    override fun start() {
        if (synth.isRunning) return
        Logger.info("Starting JSyn Audio Engine...")
        synth.add(lineOut)
        
        // Add and connect voices
        voices.forEach { voice ->
            synth.add(voice)
            // Mix to Stereo L/R
            voice.outputPort.connect(0, lineOut.input, 0)
            voice.outputPort.connect(0, lineOut.input, 1)
            
            // Send to Monitor
            voice.outputPort.connect(peakFollower.input)
        }
        
        lineOut.start()
        synth.start()
        Logger.info("Audio Engine Started")
    }

    override fun stop() {
        Logger.info("Stopping Audio Engine...")
        synth.stop()
        lineOut.stop()
        Logger.info("Audio Engine Stopped")
    }
    
    // ... (rest of class)



    override fun setVoiceTune(index: Int, tune: Float) {
        if (index in voices.indices) {
            // Map 0.0-1.0 to Frequency Range (e.g., 50Hz to 2000Hz)
            // Exponential mapping is better for pitch
            val minFreq = 50.0
            val maxFreq = 2000.0
            val freq = minFreq * Math.pow(maxFreq / minFreq, tune.toDouble())
            voices[index].frequency.set(freq)
            // Log less frequently or debug level to avoid spam
            // Logger.debug("Voice $index Tune: $tune -> ${freq.toInt()}Hz") 
        }
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        if (index in voices.indices) {
            voices[index].gate.set(if (active) 1.0 else 0.0)
            Logger.debug("Voice ${index + 1} Gate: ${if (active) "ON" else "OFF"}")
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
        Logger.info("Playing Test Tone: ${frequency}Hz")
    }

    override fun stopTestTone() {
        testOsc.amplitude.set(0.0)
        Logger.info("Stopped Test Tone")
    }

    override fun getPeak(): Float {
        return peakFollower.output.value.toFloat()
    }

    override fun getCpuLoad(): Float {
        return synth.usage.toFloat() * 100f // Return as percentage 0-100
    }
}
