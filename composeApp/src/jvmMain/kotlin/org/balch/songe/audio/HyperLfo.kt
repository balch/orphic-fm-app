package org.balch.songe.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.Circuit
import com.jsyn.unitgen.SquareOscillator
import com.jsyn.unitgen.TriangleOscillator
import com.jsyn.unitgen.Add
import com.jsyn.unitgen.Multiply
import com.jsyn.unitgen.PassThrough

/**
 * Hyper LFO: Two Oscillators (A & B) with logical AND/OR combination.
 * Supports both Square (AND formula) and Triangle waveforms.
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
    
    // Internal Components - Square
    private val lfoASquare = SquareOscillator()
    private val lfoBSquare = SquareOscillator()
    
    // Internal Components - Triangle
    private val lfoATriangle = TriangleOscillator()
    private val lfoBTriangle = TriangleOscillator()
    
    // Logic units
    private val logicAnd = Multiply()
    private val logicOr = Add()
    private val triangleMix = Add() // Average of two triangles
    private val fmGain = Multiply()
    
    // Internal State
    private var isAndMode = true
    private var isTriangleMode = true // Default to triangle for delay mod

    init {
        // Name Ports
        frequencyA.name = "FreqA"
        frequencyB.name = "FreqB"
        output.name = "Output"
    
        // Add Units
        add(inputA)
        add(inputB)
        add(outputProxy)
        add(lfoASquare)
        add(lfoBSquare)
        add(lfoATriangle)
        add(lfoBTriangle)
        add(logicAnd)
        add(logicOr)
        add(triangleMix)
        add(fmGain)
        
        // Add Ports to Circuit
        addPort(frequencyA)
        addPort(frequencyB)
        addPort(output)
        
        // WIRING
        // Inputs -> All Oscillators
        inputA.output.connect(lfoASquare.frequency)
        inputA.output.connect(lfoATriangle.frequency)
        inputB.output.connect(lfoBSquare.frequency)
        inputB.output.connect(lfoBTriangle.frequency)
        
        // Link: A -> FM -> B (for square only)
        inputA.output.connect(fmGain.inputA)
        fmGain.inputB.set(0.0) // Link OFF
        fmGain.output.connect(lfoBSquare.frequency) // Modulate B Square
        
        // Logic Gates (Square)
        lfoASquare.output.connect(logicAnd.inputA)
        lfoBSquare.output.connect(logicAnd.inputB)
        
        lfoASquare.output.connect(logicOr.inputA)
        lfoBSquare.output.connect(logicOr.inputB)
        
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
            // Square mode: use AND/OR logic
            if (isAndMode) {
                logicAnd.output.connect(outputProxy.input)
            } else {
                logicOr.output.connect(outputProxy.input)
            }
        }
    }
    
    fun setLink(enabled: Boolean) {
        // Simple FM depth switch
        fmGain.inputB.set(if (enabled) 10.0 else 0.0) 
    }
}
