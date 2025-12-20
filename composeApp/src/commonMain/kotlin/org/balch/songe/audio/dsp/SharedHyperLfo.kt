package org.balch.songe.audio.dsp

/**
 * Shared HyperLFO implementation using DSP primitive interfaces.
 * Two Oscillators (A & B) with logical AND/OR combination.
 * Supports both Square (AND formula) and Triangle waveforms.
 */
class SharedHyperLfo(private val audioEngine: AudioEngine) {
    // Interface Units (Proxies)
    private val inputA = audioEngine.createPassThrough()
    private val inputB = audioEngine.createPassThrough()
    private val outputProxy = audioEngine.createPassThrough()
    
    // Feedback Modulation
    private val feedbackProxy = audioEngine.createPassThrough()
    private val freqAModMixer = audioEngine.createAdd() // BaseFreqA + Feedback
    private val freqBModMixer = audioEngine.createAdd() // BaseFreqB + Feedback
    
    // Public Ports (for external connection)
    val frequencyA: AudioInput get() = inputA.input
    val frequencyB: AudioInput get() = inputB.input
    val feedbackInput: AudioInput get() = feedbackProxy.input // TOTAL FB input
    val output: AudioOutput get() = outputProxy.output
    
    // Internal Components - Square
    private val lfoASquare = audioEngine.createSquareOscillator()
    private val lfoBSquare = audioEngine.createSquareOscillator()
    
    // Internal Components - Triangle
    private val lfoATriangle = audioEngine.createTriangleOscillator()
    private val lfoBTriangle = audioEngine.createTriangleOscillator()
    
    // Logic units
    private val logicAnd = audioEngine.createMultiply()
    private val logicOr = audioEngine.createAdd()
    private val triangleMix = audioEngine.createAdd() // Average of two triangles
    private val fmGain = audioEngine.createMultiply()
    
    // Internal State
    private var isAndMode = true
    private var isTriangleMode = true // Default to triangle for delay mod

    init {
        // Add Units to engine
        audioEngine.addUnit(inputA)
        audioEngine.addUnit(inputB)
        audioEngine.addUnit(outputProxy)
        audioEngine.addUnit(feedbackProxy)
        audioEngine.addUnit(freqAModMixer)
        audioEngine.addUnit(freqBModMixer)
        audioEngine.addUnit(lfoASquare)
        audioEngine.addUnit(lfoBSquare)
        audioEngine.addUnit(lfoATriangle)
        audioEngine.addUnit(lfoBTriangle)
        audioEngine.addUnit(logicAnd)
        audioEngine.addUnit(logicOr)
        audioEngine.addUnit(triangleMix)
        audioEngine.addUnit(fmGain)
        
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
