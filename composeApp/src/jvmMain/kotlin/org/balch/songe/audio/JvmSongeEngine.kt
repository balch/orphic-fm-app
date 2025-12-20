package org.balch.songe.audio

import com.jsyn.JSyn
import com.jsyn.Synthesizer
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.SineOscillator
import org.balch.songe.util.Logger

class JvmSongeEngine : SongeEngine {
    private val synth: Synthesizer = JSyn.createSynthesizer()
    private val lineOut: LineOut = LineOut()
    
    // 8 Voices with pitch ranges (0.5=bass, 1.0=mid, 2.0=high)
    private val voices = listOf(
        // Pair 1-2: Bass range
        SongeVoice(pitchMultiplier = 0.5),
        SongeVoice(pitchMultiplier = 0.5),
        // Pair 3-4: Mid range
        SongeVoice(pitchMultiplier = 1.0),
        SongeVoice(pitchMultiplier = 1.0),
        // Pair 5-6: Mid range
        SongeVoice(pitchMultiplier = 1.0),
        SongeVoice(pitchMultiplier = 1.0),
        // Pair 7-8: High range (octave up)
        SongeVoice(pitchMultiplier = 2.0),
        SongeVoice(pitchMultiplier = 2.0)
    )
    
    // Mixer
    private val mixer = LineOut() // Using LineOut as a summing point for now or direct connect
    // Actually, let's use a proper Add unit chain or just connect all to LineOut input (JSyn ports sum automatically)

    // Dual Delay & Modulation
    private val delay1 = com.jsyn.unitgen.InterpolatingDelay()
    private val delay2 = com.jsyn.unitgen.InterpolatingDelay()
    private val delay1FeedbackGain = com.jsyn.unitgen.Multiply() // Independent feedback per delay
    private val delay2FeedbackGain = com.jsyn.unitgen.Multiply()
    
    // Delay Modulation
    private val hyperLfo = HyperLfo()
    private val delay1ModMixer = com.jsyn.unitgen.MultiplyAdd() // (LFO * Depth) + BaseTime
    private val delay2ModMixer = com.jsyn.unitgen.MultiplyAdd()
    private val delayLfoDepth = com.jsyn.unitgen.PassThrough() // Shared depth scaling
    
    // Self-modulation attenuators (scale down raw audio before it modulates delay time)
    private val selfMod1Attenuator = com.jsyn.unitgen.Multiply()
    private val selfMod2Attenuator = com.jsyn.unitgen.Multiply()

    // Monitoring & FX
    private val peakFollower = com.jsyn.unitgen.PeakFollower()
    private val limiter = TanhLimiter()
    private val driveGain = com.jsyn.unitgen.Multiply()
    private val masterGain = com.jsyn.unitgen.Multiply() // Master Volume
    
    // Dry/Wet Mix for Delays
    private val dryGain = com.jsyn.unitgen.Multiply()  // Direct signal
    private val wetGain = com.jsyn.unitgen.Multiply()  // Delayed signal
    
    init {
        // FX Chain: Voices -> Delays -> Drive -> Limiter -> MasterGain -> Output
        synth.add(peakFollower)
        synth.add(limiter)
        synth.add(driveGain)
        synth.add(masterGain)
        synth.add(dryGain)
        synth.add(wetGain)
        synth.add(delay1)
        synth.add(delay2)
        synth.add(delay1FeedbackGain)
        synth.add(delay2FeedbackGain)
        synth.add(hyperLfo)
        synth.add(delay1ModMixer)
        synth.add(delay2ModMixer)
        synth.add(selfMod1Attenuator)
        synth.add(selfMod2Attenuator)
        
        peakFollower.halfLife.set(0.1)
        driveGain.inputB.set(1.0) // Default unity gain
        masterGain.inputB.set(0.7) // Default master volume 70%
        
        // Dry/Wet defaults (50/50 mix)
        dryGain.inputB.set(0.5)
        wetGain.inputB.set(0.5)
        
        // Self-modulation attenuator (0.02 = only 2% of audio signal reaches mod input)
        selfMod1Attenuator.inputB.set(0.02)
        selfMod2Attenuator.inputB.set(0.02)
        // Wire delay outputs to attenuators
        delay1.output.connect(selfMod1Attenuator.inputA)
        delay2.output.connect(selfMod2Attenuator.inputA)
        
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
        
        // 1. Voices will connect to Delay Inputs AND Dry Path (in start())
        
        // 2. Delays -> WetGain -> DriveGain (wet path)
        delay1.output.connect(wetGain.inputA)
        delay2.output.connect(wetGain.inputA)
        wetGain.output.connect(driveGain.inputA)
        
        // 3. Voices -> DryGain -> DriveGain (dry path, wired in start())
        dryGain.output.connect(driveGain.inputA)
        
        // 4. Drive Output -> Limiter
        driveGain.output.connect(limiter.input)
        
        // 4. Limiter -> Master Gain
        limiter.output.connect(masterGain.inputA)
        
        // 5. Master Gain -> LineOut & Monitor
        masterGain.output.connect(0, lineOut.input, 0)
        masterGain.output.connect(0, lineOut.input, 1)
        masterGain.output.connect(peakFollower.input)
        
        // Connect PeakFollower output to LineOut (at 0 amplitude) so it gets processed
        // This forces JSyn to include it in the audio graph
        peakFollower.output.connect(0, lineOut.input, 0)
        
        // 6. Independent Feedback Loops per Delay
        delay1.output.connect(delay1FeedbackGain.inputA)
        delay1FeedbackGain.output.connect(delay1.input)
        
        delay2.output.connect(delay2FeedbackGain.inputA)
        delay2FeedbackGain.output.connect(delay2.input)
        
        // 7. Voice FM Cross-Modulation (Pair-wise)
        // INITIAL STATE: OFF (handled by DuoModSource default in ViewModel, but we should init safely here if we want)
        // Leaving disconnected by default. Logic is controlled by setDuoModSource.
    }
    // ... (rest of start() remains mostly same, ensuring voices go to driveGain) ...
    
    override fun setDrive(amount: Float) {
        // Drive controls TanhLimiter's internal drive for saturation
        // 0.0 = 1x (clean), 1.0 = 50x (extreme distortion)
        val driveVal = 1.0 + (amount * 49.0)
        limiter.drive.set(driveVal)
    }

    override fun setMasterVolume(amount: Float) {
        masterGain.inputB.set(amount.toDouble())
    }

    override fun setDelayTime(index: Int, time: Float) {
        // Time 0.0-1.0 mapped to 0.01s - 0.8s
        // Logarithmic feel helps fine tuning short delays
        val delaySeconds = 0.01 + (time * 0.79)
        
        if (index == 0) {
            // delay1.delay.set(delaySeconds) -> No, we act on the ModMixer inputC (Base)
            delay1ModMixer.inputC.set(delaySeconds)
        } else {
            delay2ModMixer.inputC.set(delaySeconds)
        }
    }

    override fun setDelayFeedback(amount: Float) {
        // Cap at 70% for stability with modulation effects
        val fb = amount * 0.7
        delay1FeedbackGain.inputB.set(fb)
        delay2FeedbackGain.inputB.set(fb)
    }
    
    override fun setDelayMix(amount: Float) {
        // 0.0 = 100% Dry, 1.0 = 100% Wet
        // Use equal-power crossfade for smooth transitions
        val wetLevel = amount
        val dryLevel = 1.0f - amount
        dryGain.inputB.set(dryLevel.toDouble())
        wetGain.inputB.set(wetLevel.toDouble())
    }

    override fun setDelayModDepth(index: Int, amount: Float) {
         // Scale depth based on delay time? Or absolute?
         // Absolute is safer for "wild" effects. 
         // Let's allow full swing +/- 0.5s if maxed.
         val depth = amount * 0.5 
         if (index == 0) {
             delay1ModMixer.inputB.set(depth)
         } else {
             delay2ModMixer.inputB.set(depth)
         }
    }
    
    override fun setDelayModSource(index: Int, isLfo: Boolean) {
        // Switch ModMixer InputA source
        // LFO -> hyperLfo.output
        // Self -> delayX.output
        
        val targetMixer = if (index == 0) delay1ModMixer else delay2ModMixer
        val selfSource = if (index == 0) delay1.output else delay2.output
        
        targetMixer.inputA.disconnectAll()
        
        if (isLfo) {
            hyperLfo.output.connect(targetMixer.inputA)
             Logger.debug { "Delay $index Mod Source: LFO" }
        } else {
            // Use attenuated self-modulation signal
            val attenuatedSelf = if (index == 0) selfMod1Attenuator.output else selfMod2Attenuator.output
            attenuatedSelf.connect(targetMixer.inputA)
             Logger.debug { "Delay $index Mod Source: SELF (attenuated)" }
        }
    }
    
    override fun setDelayLfoWaveform(isTriangle: Boolean) {
        // When isTriangle=true, use smooth triangle wave for delay modulation
        // When isTriangle=false, use square wave (AND formula) for rhythmic modulation
        hyperLfo.setTriangleMode(isTriangle)
        Logger.debug { "Delay LFO Waveform: ${if (isTriangle) "TRIANGLE" else "SQUARE"}" }
    }

    override fun setDelay(time: Float, feedback: Float) {
        // Backward compatibility
        setDelayTime(0, time)
        setDelayTime(1, time)
        setDelayFeedback(feedback)
    }

    override fun start() {
        if (synth.isRunning) return
        Logger.info { "Starting JSyn Audio Engine..." }
        synth.add(lineOut)
        
        // Add and connect voices
        voices.forEach { voice ->
            synth.add(voice)
            
            // VOICES -> DELAYS (wet path)
            voice.outputPort.connect(delay1.input)
            voice.outputPort.connect(delay2.input)
            
            // VOICES -> DRY GAIN (dry path)
            voice.outputPort.connect(dryGain.inputA)
        }
        
        lineOut.start()
        synth.start()
        Logger.info { "Audio Engine Started" }
        Logger.info { "Initial Gains - Master: ${"%.2f".format(masterGain.inputB.value)}, Drive: ${"%.2f".format(driveGain.inputB.value)}" }
    }
    override fun stop() {
        Logger.info { "Stopping Audio Engine..." }
        synth.stop()
        lineOut.stop()
        Logger.info { "Audio Engine Stopped" }
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
            Logger.info { "Voice ${index + 1} Gate: ${if (active) "ON" else "OFF"} | Master: ${"%.2f".format(masterGain.inputB.value)} | Drive: ${"%.2f".format(driveGain.inputB.value)}" }
        }
    }

    override fun setVoiceFeedback(index: Int, amount: Float) {
        // TODO: Implement Feedback routing later
    }
    
    override fun setVoiceFmDepth(index: Int, amount: Float) {
        // Set FM modulation depth (0.0 - 1.0)
        voices[index].fmDepth.set(amount.toDouble())
    }
    
    override fun setVoiceEnvelopeMode(index: Int, isFast: Boolean) {
        voices[index].setEnvelopeMode(isFast)
        Logger.debug { "Voice ${index + 1} Envelope: ${if (isFast) "FAST" else "SLOW"}" }
    }
    
    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        // Set waveform (0.0=triangle, 1.0=square) for both voices in pair
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1
        voices[voiceA].sharpness.set(sharpness.toDouble())
        voices[voiceB].sharpness.set(sharpness.toDouble())
        Logger.debug { "Pair ${pairIndex + 1} Sharpness: ${"%.2f".format(sharpness)}" }
    }

    override fun setGroupPitch(groupIndex: Int, pitch: Float) {
        // TODO: Implement Group Pitch Logic
    }

    override fun setGroupFm(groupIndex: Int, amount: Float) {
        // TODO: Implement FM Routing logic
    }


    override fun setDuoModSource(duoIndex: Int, source: org.balch.songe.audio.ModSource) {
        // Duo Index 0..3 corresponds to Voice Pairs 0-1, 2-3, 4-5, 6-7
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1
        
        // Clear existing modulation sources
        voices[voiceA].modInput.disconnectAll()
        voices[voiceB].modInput.disconnectAll()
        
        when (source) {
            org.balch.songe.audio.ModSource.OFF -> {
                Logger.debug { "Duo $duoIndex Mod Source: OFF" }
            }
            org.balch.songe.audio.ModSource.LFO -> {
                // Route HyperLFO to Mod Inputs
                hyperLfo.output.connect(voices[voiceA].modInput)
                hyperLfo.output.connect(voices[voiceB].modInput)
                Logger.debug { "Duo $duoIndex Mod Source: LFO" }
            }
            org.balch.songe.audio.ModSource.VOICE_FM -> {
                // Route Partner Voice to Mod Input (Cross-Modulation 0<->1)
                voices[voiceA].outputPort.connect(voices[voiceB].modInput)
                voices[voiceB].outputPort.connect(voices[voiceA].modInput)
                Logger.debug { "Duo $duoIndex Mod Source: VOICE_FM (Cross-Mod)" }
            }
        }
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
        Logger.debug { "HyperLFO Mode: ${if(andMode) "AND" else "OR"}" }
    }
    
    override fun setHyperLfoLink(active: Boolean) {
        hyperLfo.setLink(active)
        Logger.debug { "HyperLFO Link: $active" }
    }

    // Test tone is now just overriding Voice 0 for simplicity if needed, or we keep the separate osc
    private val testOsc = SineOscillator()
    override fun playTestTone(frequency: Float) {
        if (!synth.isRunning) start()
        synth.add(testOsc)
        // Route through FX chain so PeakFollower sees it
        testOsc.output.connect(delay1.input) 
        testOsc.frequency.set(frequency.toDouble())
        testOsc.amplitude.set(0.5)
        Logger.info { "Playing Test Tone: ${frequency}Hz (through FX chain)" }
    }

    override fun stopTestTone() {
        testOsc.amplitude.set(0.0)
        Logger.info { "Stopped Test Tone" }
    }

    override fun getPeak(): Float {
        // PeakFollower.current holds the tracked peak value
        val peak = peakFollower.current.value.toFloat()
        // Only log significant peaks to reduce spam
        if (peak > 0.5f) {
            Logger.debug { "PEAK: ${"%.2f".format(peak)}" }
        }
        return peak
    }

    override fun getCpuLoad(): Float {
        return synth.usage.toFloat() * 100f // Return as percentage 0-100
    }
}
