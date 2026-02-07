package org.balch.orpheus.core.audio.dsp

import kotlin.math.pow

/**
 * Shared voice implementation using DSP primitive interfaces.
 * This class contains the voice wiring logic in a platform-independent way.
 */
class DspVoice(
    private val audioEngine: AudioEngine,
    private val factory: DspFactory,
    private val pitchMultiplier: Double = 1.0 // 0.5=bass, 1.0=mid, 2.0=high
) {
    // Dual Oscillators for waveform morphing
    private val triangleOsc = factory.createTriangleOscillator()
    private val squareOsc = factory.createSquareOscillator()

    // Waveform Crossfade: (triangle * (1-sharp)) + (square * sharp)
    private val sharpnessProxy = factory.createPassThrough()
    private val triangleGain = factory.createMultiply() // Triangle * (1 - sharpness)
    private val squareGain = factory.createMultiply()   // Square * sharpness
    private val oscMixer = factory.createAdd()          // Sum both

    // Sharpness inversion: 1 - sharpness
    private val sharpnessInverter = factory.createMultiplyAdd() // (-1 * sharpness) + 1

    // Envelope for gating
    private val ampEnv = factory.createEnvelope()

    // VCA: Multiply mixed oscillator by envelope
    private val vca = factory.createMultiply()
    
    // Wobble: Real-time volume modulation from finger movement
    private val wobbleRamp = factory.createLinearRamp()  // Smooth parameter changes
    private val wobbleGain = factory.createMultiply()
    
    // Volume: Programmable volume control (per-voice or quad-level)
    private val volumeRamp = factory.createLinearRamp()  // Smooth volume changes
    private val volumeGain = factory.createMultiply()

    // Plaits engine source
    private val plaitsUnit = factory.createPlaitsUnit()

    // Source switching: crossfade between oscillators and Plaits
    private val oscGain = factory.createMultiply()       // oscMixer * (1 or 0)
    private val plaitsGain = factory.createMultiply()     // plaitsUnit * (0 or 1)
    private val sourceSelector = factory.createAdd()      // oscGain + plaitsGain → vca

    // Gate fanout: splits gate to envelope AND plaits trigger
    private val gateFanout = factory.createPassThrough()

    // Envelope Follower for voice coupling
    private val envelopeFollower = factory.createPeakFollower()

    // Coupling: Partner voice envelope modulates our frequency
    private val couplingScaler = factory.createMultiply() // PartnerEnvelope * CouplingDepth
    private val couplingMixer =
        factory.createAdd()       // Add coupling modulation to vibrato chain

    // FM: (ModInput * FmDepth * Scaling) + (BaseFreq * PitchMult) -> Osc.frequency
    private val fmDepthControl = factory.createMultiply()
    private val fmFreqMixer = factory.createMultiplyAdd() // (FmSignal * 200Hz) + ActualFreq
    private val pitchScaler = factory.createMultiply()    // BaseFreq * PitchMultiplier
    
    // CV Pitch: (CvInput * CvDepth) + ScaledFreq
    private val cvPitchScaler = factory.createMultiply()
    private val cvPitchMixer = factory.createAdd()
    
    // Direct frequency: Bypasses pitchScaler for note automation (Hz value goes straight through)
    private val directFreqMixer = factory.createAdd()     // Adds direct freq to scaled freq

    // Vibrato: LFO input scaled and added to frequency
    private val vibratoScaler = factory.createMultiply() // LFO * depth
    private val vibratoMixer = factory.createAdd()       // Base freq + vibrato

    // Bender: Pitch bend input scaled and added to frequency
    private val benderScaler = factory.createMultiply()  // Bend signal * depth
    private val benderMixer = factory.createAdd()        // Vibrato freq + bend

    // VCA control (envelope + hold)
    private val holdRamp = factory.createLinearRamp()  // Smooth hold changes
    private val vcaControlMixer = factory.createAdd()
    
    // State for Hold-Envelope interaction
    private var currentEnvelopeSpeed = 0.5f
    private var isTriggerMode = false
    private var currentHoldLevel = 0.0

    // Exposed ports for external wiring
    val frequency: AudioInput get() = pitchScaler.inputA           // Base frequency (through pitchScaler)
    val directFrequency: AudioInput get() = directFreqMixer.inputB // Direct Hz (bypasses pitchScaler)
    val gate: AudioInput get() = gateFanout.input                  // Gate trigger (fans out to envelope + plaits)
    val modInput: AudioInput get() = fmDepthControl.inputA         // FM signal input
    val fmDepth: AudioInput get() = fmDepthControl.inputB          // FM depth (0-1)
    val sharpness: AudioInput get() = sharpnessProxy.input         // Waveform (0=tri, 1=sq)
    val vibratoInput: AudioInput get() = vibratoScaler.inputA      // Vibrato LFO input
    val vibratoDepth: AudioInput get() = vibratoScaler.inputB      // Vibrato depth (Hz range)
    val benderInput: AudioInput get() = benderScaler.inputA        // Bender signal input
    val benderDepth: AudioInput get() = benderScaler.inputB        // Bender depth (Hz range)
    val couplingInput: AudioInput get() = couplingScaler.inputA    // Partner envelope input
    val couplingDepth: AudioInput get() = couplingScaler.inputB    // Coupling depth (Hz range)
    val holdLevel: AudioInput get() = vcaControlMixer.inputB       // Hold/drone level
    val cvPitchInput: AudioInput get() = cvPitchScaler.inputA      // CV input (0-1)
    val cvPitchDepth: AudioInput get() = cvPitchScaler.inputB      // Depth in Hz

    // Outputs - volumeGain is the final output stage
    val output: AudioOutput get() = volumeGain.output
    val envelopeOutput: AudioOutput get() = envelopeFollower.output

    // Plaits engine access
    val plaits: PlaitsUnit get() = plaitsUnit

    fun setEngineActive(active: Boolean) {
        oscGain.inputB.set(if (active) 0.0 else 1.0)
        plaitsGain.inputB.set(if (active) 1.0 else 0.0)
    }

    /**
     * Get the current envelope level (0-1 range) for visualization.
     * This is the smoothed output level of the voice.
     */
    fun getCurrentLevel(): Float = envelopeFollower.getCurrent().toFloat().coerceIn(0f, 1f)
    
    /**
     * Set wobble multiplier for real-time volume modulation.
     * Called continuously during pulse button drag.
     * Uses LinearRamp for click-free transitions.
     * @param multiplier Value around 1.0 (0.7-1.3 typical range for ±30%)
     */
    fun setWobbleMultiplier(multiplier: Double) {
        wobbleRamp.input.set(multiplier)
    }
    
    /**
     * Set voice volume using a smooth ramp to avoid clicks.
     * Resets ramp time to default (50ms) for quick transitions.
     * @param volume 0.0 to 1.0 volume level
     */
    fun setVolume(volume: Double) {
        volumeRamp.time.set(DEFAULT_VOLUME_RAMP_TIME)
        volumeRamp.input.set(volume)
    }
    
    /**
     * Fade voice volume to a target level over a specified duration.
     * Uses LinearRamp for sample-accurate, click-free transitions.
     * @param targetVolume Target volume level (0.0 to 1.0)
     * @param durationSeconds Duration of the fade in seconds
     */
    fun fadeVolume(targetVolume: Double, durationSeconds: Double) {
        volumeRamp.time.set(durationSeconds)
        volumeRamp.input.set(targetVolume)
    }
    


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
        audioEngine.addUnit(wobbleRamp)
        audioEngine.addUnit(wobbleGain)
        audioEngine.addUnit(volumeRamp)
        audioEngine.addUnit(volumeGain)
        audioEngine.addUnit(envelopeFollower)
        audioEngine.addUnit(couplingScaler)
        audioEngine.addUnit(couplingMixer)
        audioEngine.addUnit(fmDepthControl)
        audioEngine.addUnit(fmFreqMixer)
        audioEngine.addUnit(pitchScaler)
        audioEngine.addUnit(cvPitchScaler)
        audioEngine.addUnit(cvPitchMixer)
        audioEngine.addUnit(directFreqMixer)
        audioEngine.addUnit(vibratoScaler)
        audioEngine.addUnit(vibratoMixer)
        audioEngine.addUnit(benderScaler)
        audioEngine.addUnit(benderMixer)
        audioEngine.addUnit(holdRamp)
        audioEngine.addUnit(vcaControlMixer)
        audioEngine.addUnit(plaitsUnit)
        audioEngine.addUnit(oscGain)
        audioEngine.addUnit(plaitsGain)
        audioEngine.addUnit(sourceSelector)
        audioEngine.addUnit(gateFanout)

        // Envelope follower setup - longer halflife for better hold/sustain detection
        envelopeFollower.setHalfLife(0.15) // 150ms response time for sustained signals

        // Set oscillator amplitudes
        triangleOsc.amplitude.set(0.3)
        squareOsc.amplitude.set(0.3)

        // Default Envelope Settings (Fast/Percussive)
        setEnvelopeSpeed(0.0f)

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

        // Direct frequency mixer: pitchScaler.output + directFrequency -> vibratoMixer
        // This allows note automation to bypass pitchScaler entirely
        pitchScaler.output.connect(cvPitchMixer.inputA)
        cvPitchScaler.output.connect(cvPitchMixer.inputB)
        cvPitchMixer.output.connect(directFreqMixer.inputA)
        
        directFreqMixer.inputB.set(0.0)  // Default: no direct frequency override
        
        directFreqMixer.output.connect(vibratoMixer.inputA)
        vibratoScaler.output.connect(vibratoMixer.inputB)

        // Bender wiring: vibrato output + bend modulation
        vibratoMixer.output.connect(benderMixer.inputA)
        benderScaler.output.connect(benderMixer.inputB)

        benderMixer.output.connect(couplingMixer.inputA)
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

        // Default bender depth = base frequency (so bend multiplies correctly)
        benderDepth.set(100.0)  // 100Hz modulation depth at full bend

        // Default coupling depth = 0 (no partner influence)
        couplingDepth.set(0.0)
        
        // Default CV pitch depth = 0
        cvPitchDepth.set(0.0)

        // Source switching: oscMixer → oscGain, plaitsUnit → plaitsGain, both → sourceSelector → VCA
        oscMixer.output.connect(oscGain.inputA)
        oscGain.inputB.set(1.0)  // Default: oscillators active
        plaitsUnit.output.connect(plaitsGain.inputA)
        plaitsGain.inputB.set(0.0)  // Default: Plaits inactive
        oscGain.output.connect(sourceSelector.inputA)
        plaitsGain.output.connect(sourceSelector.inputB)
        sourceSelector.output.connect(vca.inputA)

        // Gate fanout: splits gate to envelope AND plaits trigger
        gateFanout.output.connect(ampEnv.input)
        gateFanout.output.connect(plaitsUnit.triggerInput)

        // VCA inputB = Envelope + HoldLevel (ramped for click-free transitions)
        ampEnv.output.connect(vcaControlMixer.inputA)
        holdRamp.time.set(0.02) // 20ms ramp for smooth hold transitions
        holdRamp.input.set(0.0)  // Default hold = 0
        holdRamp.output.connect(vcaControlMixer.inputB)
        vcaControlMixer.output.connect(vca.inputB)

        // Wire VCA output through wobbleGain for finger modulation
        vca.output.connect(wobbleGain.inputA)
        
        // Configure wobble ramp (10ms for responsive but click-free transitions)
        wobbleRamp.time.set(0.01)
        wobbleRamp.input.set(1.0)  // Start at unity gain
        wobbleRamp.output.connect(wobbleGain.inputB)
        
        // Wire wobbleGain through volumeGain for final volume control
        wobbleGain.output.connect(volumeGain.inputA)
        
        // Configure volume ramp (50ms for smooth quad volume changes)
        volumeRamp.time.set(0.05)
        volumeRamp.input.set(1.0)  // Start at full volume
        volumeRamp.output.connect(volumeGain.inputB)
        
        // Wire volumeGain output to envelope follower for coupling
        volumeGain.output.connect(envelopeFollower.input)

        // Default frequency
        frequency.set(220.0)
    }

    fun setEnvelopeSpeed(speed: Float) {
        currentEnvelopeSpeed = speed
        // Ease-in curve (quadratic) for more musical feel
        // Lower values give finer fast control, higher values expand to drone
        val eased = speed * speed

        // Interpolate between Fast (0) and Slow (1)
        // Fast: attack=0.005s, decay=0.05s, sustain=0.8, release=0.1s
        // Slow: attack=3.0s, decay=3.0s, sustain=1.0, release=4.0s (long drone)
        val attack = 0.005 + (eased * (3.0 - 0.005))
        val decay = 0.05 + (eased * (3.0 - 0.05))  // Increased for more linger
        val sustain = if (isTriggerMode) 0.0 else (0.8 + (eased * 0.2)) // 0.8 → 1.0 (full sustain at slow)
        val release = 0.1 + (eased * (4.0 - 0.1))  // Longer release for drone

        ampEnv.setAttack(attack)
        ampEnv.setDecay(decay)
        ampEnv.setSustain(sustain)
        ampEnv.setRelease(release)

        // Re-apply hold with new speed scaling
        applyScaledHold()
    }

    /**
     * Set hold level with speed-based scaling.
     * Like classic drone synths: slow envelopes make Hold more effective (lower threshold to drone).
     */
    fun setHoldLevel(level: Double) {
        currentHoldLevel = level
        applyScaledHold()
    }

    fun setTriggerMode(enabled: Boolean) {
        if (isTriggerMode == enabled) return
        isTriggerMode = enabled
        // Re-apply envelope speed settings to update sustain/mode
        setEnvelopeSpeed(currentEnvelopeSpeed)
    }

    private fun applyScaledHold() {
        // Severe ease-in curve: At fastest envelope speed (0), hold values are raised to a high power,
        // making low values produce almost no output while high values still reach full range.
        // As envelope speed increases toward 1 (slow/drone mode), the curve becomes more linear.
        // 
        // Exponent interpolation: speed=0 → exponent=4 (severe), speed=1 → exponent=1 (linear)
        val exponent = FAST_EASE_EXPONENT - (currentEnvelopeSpeed * (FAST_EASE_EXPONENT - 1.0))
        val easedHold = currentHoldLevel.pow(exponent)
        
        // Scale factor: slow mode (speed=1) → 2x, fast mode (speed=0) → 0.5x
        // This means in slow mode, a low Hold value still creates a drone
        // In fast mode, you need higher Hold to sustain
        val scaleFactor = 0.5 + (currentEnvelopeSpeed * 1.5) // 0.5 → 2.0
        val scaledHold = (easedHold * scaleFactor).coerceAtMost(1.0)
        holdRamp.input.set(scaledHold)  // Ramp to avoid clicks
    }
    
    companion object {
        private const val DEFAULT_VOLUME_RAMP_TIME = 0.05  // 50ms for quick transitions
        private const val FAST_EASE_EXPONENT = 4.0  // Power curve exponent at fastest envspeed (severe ease-in)
    }
}
