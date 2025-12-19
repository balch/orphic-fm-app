package org.balch.songe.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.Circuit
import com.jsyn.unitgen.EnvelopeDAHDSR
import com.jsyn.unitgen.Multiply
import com.jsyn.unitgen.PassThrough
import com.jsyn.unitgen.SineOscillator
import com.jsyn.unitgen.UnitSource

class SongeVoice : Circuit(), UnitSource {
    // Core Oscillator
    private val oscillator = SineOscillator()
    
    // Envelope / VCA (Simple Gate for now, can be expanded to full AR)
    private val ampEnv = EnvelopeDAHDSR()
    
    // Feedback handling
    private val feedbackMult = Multiply()
    private val feedbackInput = PassThrough() // Placeholder input for feedback from other voices or self
    
    // Ports accessible to outside
    val frequency: UnitInputPort = oscillator.frequency
    val amplitude: UnitInputPort = oscillator.amplitude
    val outputPort: UnitOutputPort = ampEnv.output
    val gate: UnitInputPort = ampEnv.input

    init {
        // Add units to circuit
        add(oscillator)
        add(ampEnv)
        add(feedbackMult)
        add(feedbackInput)

        // Internal wiring
        // Osc -> Envelope (VCA equivalent here)
        oscillator.output.connect(ampEnv.amplitude)
        
        // Simple Envelope Settings (Drone/Organ like)
        ampEnv.attack.set(0.05)
        ampEnv.decay.set(0.1)
        ampEnv.sustain.set(1.0)
        ampEnv.release.set(0.5)

        // Add ports to circuit for external connection
        addPort(frequency)
        addPort(amplitude)
        addPort(outputPort)
        addPort(gate)
    }
    
    override fun getOutput(): UnitOutputPort {
        return outputPort
    }
}
