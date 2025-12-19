package org.balch.songe.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.Circuit
import com.jsyn.unitgen.EnvelopeDAHDSR
import com.jsyn.unitgen.Multiply
import com.jsyn.unitgen.SineOscillator
import com.jsyn.unitgen.UnitSource

class SongeVoice : Circuit(), UnitSource {
    // Core Oscillator
    private val oscillator = SineOscillator()
    
    // Envelope for gating
    private val ampEnv = EnvelopeDAHDSR()
    
    // VCA: Multiply oscillator by envelope
    private val vca = Multiply()
    
    // Ports accessible to outside
    val frequency: UnitInputPort = oscillator.frequency
    val gate: UnitInputPort = ampEnv.input
    
    // Output comes from the VCA
    override fun getOutput(): UnitOutputPort = vca.output

    init {
        // Add units to circuit
        add(oscillator)
        add(ampEnv)
        add(vca)

        // Set oscillator amplitude (full volume to envelope)
        oscillator.amplitude.set(0.3) // Reasonable level to prevent clipping
        
        // Simple Envelope Settings (Drone/Organ like)
        ampEnv.attack.set(0.01)  // Fast attack
        ampEnv.decay.set(0.1)
        ampEnv.sustain.set(1.0) // Full sustain
        ampEnv.release.set(0.3)

        // Wire: Oscillator -> VCA inputA, Envelope -> VCA inputB
        oscillator.output.connect(vca.inputA)
        ampEnv.output.connect(vca.inputB)

        // Default frequency
        oscillator.frequency.set(220.0)
        
        // Add primary ports to circuit for external connection
        addPort(frequency, "frequency")
        addPort(gate, "gate")
        addPort(vca.output, "output")
    }
    
    val outputPort: UnitOutputPort = vca.output
}

