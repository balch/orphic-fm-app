package org.balch.songe.core.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.Add
import com.jsyn.unitgen.Circuit
import com.jsyn.unitgen.Multiply
import com.jsyn.unitgen.MultiplyAdd
import com.jsyn.unitgen.PassThrough
import com.jsyn.unitgen.SquareOscillator
import com.jsyn.unitgen.TriangleOscillator

/**
 * Hyper LFO: Two Oscillators (A & B) with logical AND/OR combination.
 * Supports both Square (AND/OR logic) and Triangle waveforms.
 * 
 * For square waves (-1 to +1), proper AND/OR logic requires:
 * 1. Convert bipolar (-1,+1) to unipolar (0,1): u = (x + 1) / 2 = x * 0.5 + 0.5
 * 2. Apply logic: AND = Ua * Ub, OR = Ua + Ub - Ua*Ub
 * 3. Convert back to bipolar: x = u * 2 - 1
 */
class HyperLfo : Circuit() {
    // Interface Units (Proxies)
    private val inputA = PassThrough()
    private val inputB = PassThrough()
    private val outputProxy = PassThrough()
    
    // Feedback Modulation
    private val feedbackProxy = PassThrough()
    private val freqAModMixer = Add() // BaseFreqA + Feedback
    private val freqBModMixer = Add() // BaseFreqB + Feedback
    
    // Public Ports (for external connection)
    val frequencyA: UnitInputPort = inputA.input
    val frequencyB: UnitInputPort = inputB.input
    val feedbackInput: UnitInputPort = feedbackProxy.input // TOTAL FB input
    val output: UnitOutputPort = outputProxy.output
    
    // Internal Components - Square
    private val lfoASquare = SquareOscillator()
    private val lfoBSquare = SquareOscillator()
    
    // Internal Components - Triangle
    private val lfoATriangle = TriangleOscillator()
    private val lfoBTriangle = TriangleOscillator()
    
    // Bipolar to Unipolar converters: u = x * 0.5 + 0.5
    private val toUnipolarA = MultiplyAdd()
    private val toUnipolarB = MultiplyAdd()
    
    // Logic units (operate on unipolar 0-1 signals)
    private val logicAnd = Multiply() // Ua * Ub
    
    // OR logic: Ua + Ub - Ua*Ub
    private val orProduct = Multiply()  // Ua * Ub
    private val orSum = Add()           // Ua + Ub
    private val orResult = MultiplyAdd() // orSum + (-1 * orProduct) = Ua + Ub - Ua*Ub
    
    // Unipolar to Bipolar converter: x = u * 2 - 1
    private val toBipolarAnd = MultiplyAdd()
    private val toBipolarOr = MultiplyAdd()
    
    private val triangleMix = Add() // Average of two triangles
    private val fmGain = Multiply()
    
    // Internal State
    private var isAndMode = true
    private var isTriangleMode = true // Default to triangle for delay mod

    init {
        // Name Ports
        frequencyA.name = "FreqA"
        frequencyB.name = "FreqB"
        feedbackInput.name = "FeedbackIn"
        output.name = "Output"
    
        // Add Units
        add(inputA)
        add(inputB)
        add(outputProxy)
        add(feedbackProxy)
        add(freqAModMixer)
        add(freqBModMixer)
        add(lfoASquare)
        add(lfoBSquare)
        add(lfoATriangle)
        add(lfoBTriangle)
        add(toUnipolarA)
        add(toUnipolarB)
        add(logicAnd)
        add(orProduct)
        add(orSum)
        add(orResult)
        add(toBipolarAnd)
        add(toBipolarOr)
        add(triangleMix)
        add(fmGain)
        
        // Add Ports to Circuit
        addPort(frequencyA)
        addPort(frequencyB)
        addPort(feedbackInput)
        addPort(output)
        
        // WIRING
        // Base Frequencies -> Mixers (inputA of each)
        inputA.output.connect(freqAModMixer.inputA)
        inputB.output.connect(freqBModMixer.inputA)
        
        // Feedback -> Both Mixers (inputB of each)
        feedbackProxy.output.connect(freqAModMixer.inputB)
        feedbackProxy.output.connect(freqBModMixer.inputB)
        
        // Mixers -> All Oscillators
        freqAModMixer.output.connect(lfoASquare.frequency)
        freqAModMixer.output.connect(lfoATriangle.frequency)
        freqBModMixer.output.connect(lfoBSquare.frequency)
        freqBModMixer.output.connect(lfoBTriangle.frequency)
        
        // Link: A -> FM -> B (for square only)
        inputA.output.connect(fmGain.inputA)
        fmGain.inputB.set(0.0) // Link OFF
        fmGain.output.connect(lfoBSquare.frequency) // Modulate B Square
        
        // ═══════════════════════════════════════════════════════════
        // BIPOLAR TO UNIPOLAR CONVERSION
        // u = x * 0.5 + 0.5 (converts -1,+1 to 0,1)
        // ═══════════════════════════════════════════════════════════
        lfoASquare.output.connect(toUnipolarA.inputA)
        toUnipolarA.inputB.set(0.5)
        toUnipolarA.inputC.set(0.5)
        
        lfoBSquare.output.connect(toUnipolarB.inputA)
        toUnipolarB.inputB.set(0.5)
        toUnipolarB.inputC.set(0.5)
        
        // ═══════════════════════════════════════════════════════════
        // AND LOGIC: Ua * Ub (works correctly for 0/1 values)
        // ═══════════════════════════════════════════════════════════
        toUnipolarA.output.connect(logicAnd.inputA)
        toUnipolarB.output.connect(logicAnd.inputB)
        
        // Convert AND result back to bipolar: x = u * 2 - 1
        logicAnd.output.connect(toBipolarAnd.inputA)
        toBipolarAnd.inputB.set(2.0)
        toBipolarAnd.inputC.set(-1.0)
        
        // ═══════════════════════════════════════════════════════════
        // OR LOGIC: Ua + Ub - Ua*Ub
        // ═══════════════════════════════════════════════════════════
        // First: Ua * Ub
        toUnipolarA.output.connect(orProduct.inputA)
        toUnipolarB.output.connect(orProduct.inputB)
        
        // Second: Ua + Ub
        toUnipolarA.output.connect(orSum.inputA)
        toUnipolarB.output.connect(orSum.inputB)
        
        // Third: (Ua + Ub) + (-1 * Ua*Ub) = Ua + Ub - Ua*Ub
        orProduct.output.connect(orResult.inputA)
        orResult.inputB.set(-1.0)
        orSum.output.connect(orResult.inputC)
        
        // Convert OR result back to bipolar: x = u * 2 - 1
        orResult.output.connect(toBipolarOr.inputA)
        toBipolarOr.inputB.set(2.0)
        toBipolarOr.inputC.set(-1.0)
        
        // Triangle Mix (average of two triangle waves)
        lfoATriangle.output.connect(triangleMix.inputA)
        lfoBTriangle.output.connect(triangleMix.inputB)
        
        // Initial Output: Triangle
        triangleMix.output.connect(outputProxy.input)
    }

    fun setMode(andMode: Boolean) {
        if (isAndMode == andMode) return
        isAndMode = andMode
        updateOutput()
    }
    
    fun setTriangleMode(triangleMode: Boolean) {
        if (isTriangleMode == triangleMode) return
        isTriangleMode = triangleMode
        updateOutput()
    }
    
    private fun updateOutput() {
        outputProxy.input.disconnectAll()
        
        if (isTriangleMode) {
            // Triangle mode: use averaged triangle waves
            triangleMix.output.connect(outputProxy.input)
        } else {
            // Square mode: use AND/OR logic (with proper bipolar conversion)
            if (isAndMode) {
                toBipolarAnd.output.connect(outputProxy.input)
            } else {
                toBipolarOr.output.connect(outputProxy.input)
            }
        }
    }
    
    fun setLink(enabled: Boolean) {
        // Simple FM depth switch
        fmGain.inputB.set(if (enabled) 10.0 else 0.0) 
    }
}
