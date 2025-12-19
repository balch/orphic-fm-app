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

    // Monitoring & FX
    private val peakFollower = com.jsyn.unitgen.PeakFollower()
    private val limiter = TanhLimiter()
    private val driveGain = com.jsyn.unitgen.Multiply()
    
    // Dual Delay
    private val delay1 = com.jsyn.unitgen.InterpolatingDelay()
    private val delay2 = com.jsyn.unitgen.InterpolatingDelay()
    private val delayFeedbackGain = com.jsyn.unitgen.Multiply()

    
    init {
        // FX Chain: Mix -> Drive -> Delays -> Limiter -> Output
        synth.add(peakFollower)
        synth.add(limiter)
        synth.add(driveGain)
        synth.add(delay1)
        synth.add(delay2)
        synth.add(delayFeedbackGain)
        
        peakFollower.halfLife.set(0.1)
        driveGain.inputB.set(1.0) // Default unity gain
        
        // Delay Defaults
        delay1.allocate(44100) // 1 second max buffer
        delay2.allocate(44100)
        delay1.delay.set(0.0) // Init off
        delay2.delay.set(0.0) 
        
        // Connect Drive Output to Delays (Parallel)
        driveGain.output.connect(delay1.input)
        driveGain.output.connect(delay2.input)
        
        // Connect Delays to Limiter
        delay1.output.connect(limiter.input)
        delay2.output.connect(limiter.input)
        
        // Basic Feedback Loop (Mono sum for now, can be complex later)
        delay1.output.connect(delayFeedbackGain.inputA)
        delay2.output.connect(delayFeedbackGain.inputA)
        delayFeedbackGain.output.connect(delay1.input) // Feedback mix back in
        delayFeedbackGain.output.connect(delay2.input)
    }
    // ... (rest of start() remains mostly same, ensuring voices go to driveGain) ...
    
    override fun setDelay(time: Float, feedback: Float) {
        // Time 0.0-1.0 mapped to 0.01s - 0.8s
        val delayTime = 0.01 + (time * 0.79)
        
        // Set slightly different times for stereo width feel
        delay1.delay.set(delayTime)
        delay2.delay.set(delayTime * 1.1) // Slight offset for width
        
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
            
            // Send to Drive Gain Input (Mix Bus)
            voice.outputPort.connect(driveGain.inputA)
        }
        
        // Limiter Output -> Master Out & Monitor
        limiter.output.connect(0, lineOut.input, 0)
        limiter.output.connect(0, lineOut.input, 1)
        limiter.output.connect(peakFollower.input)

        
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
