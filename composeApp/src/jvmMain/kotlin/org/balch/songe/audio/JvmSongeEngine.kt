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

    // Dual Delay & Modulation
    private val delay1 = com.jsyn.unitgen.InterpolatingDelay()
    private val delay2 = com.jsyn.unitgen.InterpolatingDelay()
    private val delayFeedbackGain = com.jsyn.unitgen.Multiply()
    
    // Delay Modulation
    private val hyperLfo = HyperLfo()
    private val delay1ModMixer = com.jsyn.unitgen.MultiplyAdd() // (LFO * Depth) + BaseTime
    private val delay2ModMixer = com.jsyn.unitgen.MultiplyAdd()
    private val delayLfoDepth = com.jsyn.unitgen.PassThrough() // Shared depth scaling

    // Monitoring & FX
    private val peakFollower = com.jsyn.unitgen.PeakFollower()
    private val limiter = TanhLimiter()
    private val driveGain = com.jsyn.unitgen.Multiply()
    
    init {
        // FX Chain: Voices -> Delays -> Drive -> Limiter -> Output
        synth.add(peakFollower)
        synth.add(limiter)
        synth.add(driveGain)
        synth.add(delay1)
        synth.add(delay2)
        synth.add(delayFeedbackGain)
        synth.add(hyperLfo)
        synth.add(delay1ModMixer)
        synth.add(delay2ModMixer)
        
        peakFollower.halfLife.set(0.1)
        driveGain.inputB.set(1.0) // Default unity gain
        
        // Delay Defaults
        delay1.allocate(44100) // 1 second max buffer
        delay2.allocate(44100)
        
        // Delay Modulation Wiring:
        // LFO/Self -> Mixer Input A
        // Depth -> Mixer Input B
        // BaseTime -> Mixer Input C
        // Mixer Output -> Delay.delay
        
        // Default: LFO to Mod Mixer
        hyperLfo.output.connect(delay1ModMixer.inputA)
        hyperLfo.output.connect(delay2ModMixer.inputA)
        
        // Mod Depth (default 0)
        delay1ModMixer.inputB.set(0.0)
        delay2ModMixer.inputB.set(0.0)
        
        // Connect Mod Mixers to Delays
        delay1ModMixer.output.connect(delay1.delay)
        delay2ModMixer.output.connect(delay2.delay)
        
        
        // ROUTING ---------------------------------------------------------
        
        // 1. Voices will connect to Delay Inputs (in start())
        
        // 2. Delays -> Drive Input (Summed)
        // Note: Using driveGain inputA as the summing bus for the delays
        delay1.output.connect(driveGain.inputA)
        delay2.output.connect(driveGain.inputA)
        
        // 3. Drive Output -> Limiter
        driveGain.output.connect(limiter.input)
        
        // 4. Limiter -> Master Out & Monitor
        limiter.output.connect(0, lineOut.input, 0)
        limiter.output.connect(0, lineOut.input, 1)
        limiter.output.connect(peakFollower.input)
        
        // 5. Feedback Loop (Tapped from Delay Output BEFORE Drive? Or After?)
        // Usually feedback is from the delay line output itself.
        delay1.output.connect(delayFeedbackGain.inputA)
        delay2.output.connect(delayFeedbackGain.inputA)
        delayFeedbackGain.output.connect(delay1.input) 
        delayFeedbackGain.output.connect(delay2.input)
    }
    // ... (rest of start() remains mostly same, ensuring voices go to driveGain) ...
    
    override fun setDelay(time: Float, feedback: Float) {
        // Time 0.0-1.0 mapped to 0.01s - 0.8s
        val delayTime = 0.01 + (time * 0.79)
        
        // Set BASE TIME on mod mixer
        delay1ModMixer.inputC.set(delayTime)
        delay2ModMixer.inputC.set(delayTime * 1.1) // Stereo offset
        
        // For now, hardcode mod depth slightly to test LFO
        // Later we need setDelayModDepth()
        delay1ModMixer.inputB.set(delayTime * 0.1) // 10% modulation depth
        delay2ModMixer.inputB.set(delayTime * 0.1)
        
        delayFeedbackGain.inputB.set(feedback * 0.9) // Cap feedback < 1.0
        
        Logger.debug("Delay: ${delayTime}s, FB: $feedback")
    }

    override fun start() {
        if (synth.isRunning) return
        Logger.info("Starting JSyn Audio Engine...")
        synth.add(lineOut)
        
        // Add and connect voices
        voices.forEach { voice ->
            synth.add(voice)
            
            // VOICES -> DELAYS (Parallel)
            voice.outputPort.connect(delay1.input)
            voice.outputPort.connect(delay2.input)
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
        // Map 0.0-1.0 to Gain 1.0 - 50.0 (Hard Distortion)
        // Logarithmic feel might be better, or linear for "drive"
        val gain = 1.0 + (amount * 49.0)
        driveGain.inputB.set(gain)
        Logger.debug("Drive Set: ${amount} (Gain: x${gain.toInt()})")
    }

    override fun setHyperLfoFreq(index: Int, frequency: Float) {
        // Map 0-1 to LFO frequency (e.g., 0.1Hz to 20Hz)
        val lfoFreq = 0.1 + (frequency * 19.9)
        if (index == 0) {
            hyperLfo.frequencyA.set(lfoFreq)
        } else {
            hyperLfo.frequencyB.set(lfoFreq)
        }
    }
    
    override fun setHyperLfoMode(andMode: Boolean) {
        hyperLfo.setMode(andMode)
        Logger.debug("HyperLFO Mode: ${if(andMode) "AND" else "OR"}")
    }
    
    override fun setHyperLfoLink(active: Boolean) {
        hyperLfo.setLink(active)
        Logger.debug("HyperLFO Link: $active")
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
