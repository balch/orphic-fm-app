package org.balch.songe.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.Circuit
import com.jsyn.unitgen.SquareOscillator
import com.jsyn.unitgen.Add
import com.jsyn.unitgen.Multiply
import com.jsyn.unitgen.PassThrough

/**
 * Hyper LFO: Two Oscillators (A & B) with logical AND/OR combination.
 */
class HyperLfo : Circuit() {
    // Interface Units (Proxies)
    private val inputA = PassThrough()
    private val inputB = PassThrough()
    private val outputProxy = PassThrough()
    
    // Public Ports (for external connection)
    val frequencyA: UnitInputPort = inputA.input
    val frequencyB: UnitInputPort = inputB.input
    val output: UnitOutputPort = outputProxy.output
    
    // Internal Components
    private val lfoA = SquareOscillator()
    private val lfoB = SquareOscillator()
    private val logicAnd = Multiply()
    private val logicOr = Add()
    private val fmGain = Multiply()
    
    // Internal Switch Logic
    private var isAndMode = true

    init {
        // Name Ports
        frequencyA.name = "FreqA"
        frequencyB.name = "FreqB"
        output.name = "Output"
    
        // Add Units
        add(inputA)
        add(inputB)
        add(outputProxy)
        add(lfoA)
        add(lfoB)
        add(logicAnd)
        add(logicOr)
        add(fmGain)
        
        // Add Ports to Circuit
        addPort(frequencyA)
        addPort(frequencyB)
        addPort(output)
        
        // WIRING
        // Inputs -> Oscillators
        inputA.output.connect(lfoA.frequency)
        inputB.output.connect(lfoB.frequency)
        
        // Link: A -> FM -> B
        inputA.output.connect(fmGain.inputA)
        fmGain.inputB.set(0.0) // Link OFF
        fmGain.output.connect(lfoB.frequency) // Modulate B
        
        // Logic Gates
        lfoA.output.connect(logicAnd.inputA)
        lfoB.output.connect(logicAnd.inputB)
        
        lfoA.output.connect(logicOr.inputA)
        lfoB.output.connect(logicOr.inputB)
        
        // Initial Output: AND
        logicAnd.output.connect(outputProxy.input)
    }

    fun setMode(andMode: Boolean) {
        if (isAndMode == andMode) return
        isAndMode = andMode
        
        // Re-patch output
        outputProxy.input.disconnectAll()
        if (andMode) {
            logicAnd.output.connect(outputProxy.input)
        } else {
            logicOr.output.connect(outputProxy.input)
        }
    }
    
    fun setLink(enabled: Boolean) {
        // Simple FM depth switch
        fmGain.inputB.set(if (enabled) 10.0 else 0.0) 
    }
}
