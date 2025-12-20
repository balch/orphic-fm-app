package org.balch.songe.audio.dsp

/**
 * Shared voice implementation using DSP primitive interfaces.
 * This class contains the voice wiring logic in a platform-independent way.
 */
class SharedVoice(
    private val audioEngine: AudioEngine,
    private val pitchMultiplier: Double = 1.0 // 0.5=bass, 1.0=mid, 2.0=high
) {
    // Dual Oscillators for waveform morphing
    private val triangleOsc = audioEngine.createTriangleOscillator()
    private val squareOsc = audioEngine.createSquareOscillator()
    
    // Waveform Crossfade: (triangle * (1-sharp)) + (square * sharp)
    private val sharpnessProxy = audioEngine.createPassThrough()
    private val triangleGain = audioEngine.createMultiply() // Triangle * (1 - sharpness)
    private val squareGain = audioEngine.createMultiply()   // Square * sharpness
    private val oscMixer = audioEngine.createAdd()          // Sum both
    
    // Sharpness inversion: 1 - sharpness
    private val sharpnessInverter = audioEngine.createMultiplyAdd() // (-1 * sharpness) + 1
    
    // Envelope for gating
    private val ampEnv = audioEngine.createEnvelope()
    
    // VCA: Multiply mixed oscillator by envelope
    private val vca = audioEngine.createMultiply()
    
    // Envelope Follower for voice coupling
    private val envelopeFollower = audioEngine.createPeakFollower()
    
    // Coupling: Partner voice envelope modulates our frequency
    private val couplingScaler = audioEngine.createMultiply() // PartnerEnvelope * CouplingDepth
    private val couplingMixer = audioEngine.createAdd()       // Add coupling modulation to vibrato chain
    
    // FM: (ModInput * FmDepth * Scaling) + (BaseFreq * PitchMult) -> Osc.frequency
    private val fmDepthControl = audioEngine.createMultiply()
    private val fmFreqMixer = audioEngine.createMultiplyAdd() // (FmSignal * 200Hz) + ActualFreq
    private val pitchScaler = audioEngine.createMultiply()    // BaseFreq * PitchMultiplier
    
    // Vibrato: LFO input scaled and added to frequency
    private val vibratoScaler = audioEngine.createMultiply() // LFO * depth
    private val vibratoMixer = audioEngine.createAdd()       // Base freq + vibrato
    
    // VCA control (envelope + hold)
    private val vcaControlMixer = audioEngine.createAdd()
    
    // Exposed ports for external wiring
    val frequency: AudioInput get() = pitchScaler.inputA           // Base frequency
    val gate: AudioInput get() = ampEnv.input                      // Gate trigger
    val modInput: AudioInput get() = fmDepthControl.inputA         // FM signal input
    val fmDepth: AudioInput get() = fmDepthControl.inputB          // FM depth (0-1)
    val sharpness: AudioInput get() = sharpnessProxy.input         // Waveform (0=tri, 1=sq)
    val vibratoInput: AudioInput get() = vibratoScaler.inputA      // Vibrato LFO input
    val vibratoDepth: AudioInput get() = vibratoScaler.inputB      // Vibrato depth (Hz range)
    val couplingInput: AudioInput get() = couplingScaler.inputA    // Partner envelope input
    val couplingDepth: AudioInput get() = couplingScaler.inputB    // Coupling depth (Hz range)
    val holdLevel: AudioInput get() = vcaControlMixer.inputB       // Hold/drone level
    
    // Outputs
    val output: AudioOutput get() = vca.output
    val envelopeOutput: AudioOutput get() = envelopeFollower.output
    
    init {
        // Register all units with audio engine
        audioEngine.addUnit(triangleOsc)
        audioEngine.addUnit(squareOsc)
        audioEngine.addUnit(sharpnessProxy)
        audioEngine.addUnit(triangleGain)
        audioEngine.addUnit(squareGain)
        audioEngine.addUnit(oscMixer)
        audioEngine.addUnit(sharpnessInverter)
        audioEngine.addUnit(ampEnv)
        audioEngine.addUnit(vca)
        audioEngine.addUnit(envelopeFollower)
        audioEngine.addUnit(couplingScaler)
        audioEngine.addUnit(couplingMixer)
        audioEngine.addUnit(fmDepthControl)
        audioEngine.addUnit(fmFreqMixer)
        audioEngine.addUnit(pitchScaler)
        audioEngine.addUnit(vibratoScaler)
        audioEngine.addUnit(vibratoMixer)
        audioEngine.addUnit(vcaControlMixer)
        
        // Envelope follower setup
        envelopeFollower.setHalfLife(0.05) // 50ms response time
        
        // Set oscillator amplitudes
        triangleOsc.amplitude.set(0.3)
        squareOsc.amplitude.set(0.3)
        
        // Default Envelope Settings (Slow/Drone)
        setEnvelopeMode(isFast = false)
        
        // Pitch Scaling
        pitchScaler.inputB.set(pitchMultiplier)
        
        // ═══════════════════════════════════════════════════════════
        // WIRING
        // ═══════════════════════════════════════════════════════════
        
        // Sharpness Wiring:
        // sharpnessProxy -> sharpnessInverter.inputA (for 1-x calculation)
        // sharpnessProxy -> squareGain.inputB (direct)
        // sharpnessInverter.output -> triangleGain.inputB
        sharpnessProxy.output.connect(sharpnessInverter.inputA)
        sharpnessProxy.output.connect(squareGain.inputB)
        
        // Sharpness Inverter: (sharpness * -1) + 1 = (1 - sharpness)
        sharpnessInverter.inputB.set(-1.0)
        sharpnessInverter.inputC.set(1.0)
        
        // Waveform Morphing Wiring:
        triangleOsc.output.connect(triangleGain.inputA)
        sharpnessInverter.output.connect(triangleGain.inputB)
        squareOsc.output.connect(squareGain.inputA)
        triangleGain.output.connect(oscMixer.inputA)
        squareGain.output.connect(oscMixer.inputB)
        
        // Default sharpness = 0.0 (pure triangle)
        sharpness.set(0.0)
        
        // FM Wiring with Vibrato and Coupling:
        // PitchScaler -> VibratoMixer.inputA (base scaled frequency)
        // VibratoScaler -> VibratoMixer.inputB (LFO * depth)
        // VibratoMixer -> CouplingMixer.inputA
        // CouplingScaler -> CouplingMixer.inputB (partner envelope * depth)
        // CouplingMixer.output -> FmFreqMixer.inputC (final freq before FM)
        // FmDepthControl -> FmFreqMixer.inputA (FM modulation)
        // FmFreqMixer.output -> Both Oscillators
        
        pitchScaler.output.connect(vibratoMixer.inputA)
        vibratoScaler.output.connect(vibratoMixer.inputB)
        
        vibratoMixer.output.connect(couplingMixer.inputA)
        couplingScaler.output.connect(couplingMixer.inputB)
        
        couplingMixer.output.connect(fmFreqMixer.inputC)
        fmDepthControl.output.connect(fmFreqMixer.inputA)
        fmFreqMixer.inputB.set(200.0) // FM Scaling factor
        
        // Connect frequency to both oscillators
        fmFreqMixer.output.connect(triangleOsc.frequency)
        fmFreqMixer.output.connect(squareOsc.frequency)
        
        // Default FM depth = 0 (no modulation)
        fmDepth.set(0.0)
        
        // Default vibrato depth = 0 (no wobble)
        vibratoDepth.set(0.0)
        
        // Default coupling depth = 0 (no partner influence)
        couplingDepth.set(0.0)
        
        // Wire: Mixed Oscillator -> VCA inputA
        oscMixer.output.connect(vca.inputA)
        
        // VCA inputB = Envelope + HoldLevel
        ampEnv.output.connect(vcaControlMixer.inputA)
        vcaControlMixer.inputB.set(0.0) // Default hold = 0
        vcaControlMixer.output.connect(vca.inputB)
        
        // Wire VCA output to envelope follower for coupling
        vca.output.connect(envelopeFollower.input)
        
        // Default frequency
        frequency.set(220.0)
    }
    
    fun setEnvelopeMode(isFast: Boolean) {
        if (isFast) {
            // Fast: Percussive, quick attack/release
            ampEnv.setAttack(0.005)
            ampEnv.setDecay(0.05)
            ampEnv.setSustain(0.8)
            ampEnv.setRelease(0.1)
        } else {
            // Slow: Drone, slow swell
            ampEnv.setAttack(0.5)
            ampEnv.setDecay(0.2)
            ampEnv.setSustain(1.0)
            ampEnv.setRelease(0.8)
        }
    }
}
