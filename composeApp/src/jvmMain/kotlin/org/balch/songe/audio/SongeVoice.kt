package org.balch.songe.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.Add
import com.jsyn.unitgen.Circuit
import com.jsyn.unitgen.EnvelopeDAHDSR
import com.jsyn.unitgen.Multiply
import com.jsyn.unitgen.MultiplyAdd
import com.jsyn.unitgen.PassThrough
import com.jsyn.unitgen.SquareOscillator
import com.jsyn.unitgen.TriangleOscillator
import com.jsyn.unitgen.UnitSource

class SongeVoice(
    private val pitchMultiplier: Double = 1.0 // 0.5=bass, 1.0=mid, 2.0=high
) : Circuit(), UnitSource {
    // Dual Oscillators for waveform morphing
    private val triangleOsc = TriangleOscillator()
    private val squareOsc = SquareOscillator()
    
    // Waveform Crossfade: (triangle * (1-sharp)) + (square * sharp)
    private val sharpnessProxy = PassThrough() // Split sharpness signal
    private val triangleGain = Multiply() // Triangle * (1 - sharpness)
    private val squareGain = Multiply()   // Square * sharpness
    private val oscMixer = Add()          // Sum both
    
    // Sharpness inversion: 1 - sharpness
    private val sharpnessInverter = MultiplyAdd() // (-1 * sharpness) + 1
    
    // Envelope for gating
    private val ampEnv = EnvelopeDAHDSR()
    
    // VCA: Multiply mixed oscillator by envelope
    private val vca = Multiply()
    
    // FM: (ModInput * FmDepth * Scaling) + (BaseFreq * PitchMult) -> Osc.frequency
    private val fmDepthControl = Multiply()
    private val fmFreqMixer = MultiplyAdd() // (FmSignal * 200Hz) + ActualFreq
    private val pitchScaler = Multiply()    // BaseFreq * PitchMultiplier
    
    // Ports accessible to outside
    val frequency: UnitInputPort = pitchScaler.inputA // Base frequency (before pitch mult)
    val gate: UnitInputPort = ampEnv.input
    val modInput: UnitInputPort = fmDepthControl.inputA // FM signal input
    val fmDepth: UnitInputPort = fmDepthControl.inputB // FM depth (0-1)
    val sharpness: UnitInputPort = sharpnessProxy.input // Waveform (0=tri, 1=sq)
    
    // Output comes from the VCA
    override fun getOutput(): UnitOutputPort = vca.output

    init {
        // Add units to circuit
        add(triangleOsc)
        add(squareOsc)
        add(sharpnessProxy)
        add(triangleGain)
        add(squareGain)
        add(oscMixer)
        add(sharpnessInverter)
        add(ampEnv)
        add(vca)
        add(fmDepthControl)
        add(fmFreqMixer)
        add(pitchScaler)

        // Set oscillator amplitudes
        triangleOsc.amplitude.set(0.3)
        squareOsc.amplitude.set(0.3)
        
        // Default Envelope Settings (Slow/Drone)
        setEnvelopeMode(isFast = false)

        // Pitch Scaling
        pitchScaler.inputB.set(pitchMultiplier)
        
        // Sharpness Wiring:
        // User Input -> sharpnessProxy 
        // sharpnessProxy -> sharpnessInverter.inputA (for 1-x calculation)
        // sharpnessProxy -> squareGain.inputB (direct)
        // sharpnessInverter.output -> triangleGain.inputB
        
        sharpnessProxy.output.connect(sharpnessInverter.inputA)
        sharpnessProxy.output.connect(squareGain.inputB)
        
        // Sharpness Inverter: (sharpness * -1) + 1 = (1 - sharpness)
        sharpnessInverter.inputB.set(-1.0)
        sharpnessInverter.inputC.set(1.0)
        
        // Waveform Morphing Wiring:
        // Triangle -> TriangleGain.inputA
        // (1 - sharpness) -> TriangleGain.inputB
        // Square -> SquareGain.inputA
        // sharpness -> SquareGain.inputB (already connected above)
        // Both gains -> Mixer -> VCA
        triangleOsc.output.connect(triangleGain.inputA)
        sharpnessInverter.output.connect(triangleGain.inputB)
        
        squareOsc.output.connect(squareGain.inputA)
        
        triangleGain.output.connect(oscMixer.inputA)
        squareGain.output.connect(oscMixer.inputB)
        
        // Default sharpness = 0.0 (pure triangle)
        sharpness.set(0.0)

        // FM Wiring:
        // BaseFreq -> PitchScaler -> FmFreqMixer.inputC (final freq)
        // ModInput -> FmDepthControl -> FmFreqMixer.inputA (modulation)
        // FmFreqMixer.output -> Both Oscillators
        
        pitchScaler.output.connect(fmFreqMixer.inputC)
        fmDepthControl.output.connect(fmFreqMixer.inputA)
        fmFreqMixer.inputB.set(200.0) // FM Scaling factor
        
        // Connect frequency to both oscillators
        fmFreqMixer.output.connect(triangleOsc.frequency)
        fmFreqMixer.output.connect(squareOsc.frequency)
        
        // Default FM depth = 0 (no modulation)
        fmDepth.set(0.0)

        // Wire: Mixed Oscillator -> VCA inputA
        oscMixer.output.connect(vca.inputA)
        
        // VCA inputB = Envelope + HoldLevel
        // Note: HoldLevel is essentially "Min Volume"
        // We use an Add unit to sum envelope output and hold level
        val vcaControlMixer = Add()
        add(vcaControlMixer)
        
        ampEnv.output.connect(vcaControlMixer.inputA)
        // inputB of vcaControlMixer will be our hold level
        
        vcaControlMixer.output.connect(vca.inputB)

        // Default frequency
        frequency.set(220.0)
        
        // Add primary ports to circuit for external connection
        addPort(frequency, "frequency")
        addPort(gate, "gate")
        addPort(modInput, "modInput")
        addPort(fmDepth, "fmDepth")
        addPort(sharpness, "sharpness")
        
        // Expose Hold Level (using vcaControlMixer.inputB directly)
        val holdLevel: UnitInputPort = vcaControlMixer.inputB
        holdLevel.set(0.0)
        addPort(holdLevel, "holdLevel")
        
        addPort(vca.output, "output")
    }

    // Expose for JvmSongeEngine
    val holdLevelPort: UnitInputPort
        get() = (getPortByName("holdLevel") as UnitInputPort)
    
    fun setEnvelopeMode(isFast: Boolean) {
        if (isFast) {
            // Fast: Percussive, quick attack/release
            ampEnv.attack.set(0.005)
            ampEnv.decay.set(0.05)
            ampEnv.sustain.set(0.8)
            ampEnv.release.set(0.1)
        } else {
            // Slow: Drone, slow swell
            ampEnv.attack.set(0.5)
            ampEnv.decay.set(0.2)
            ampEnv.sustain.set(1.0)
            ampEnv.release.set(0.8)
        }
    }
    
    val outputPort: UnitOutputPort = vca.output
}

