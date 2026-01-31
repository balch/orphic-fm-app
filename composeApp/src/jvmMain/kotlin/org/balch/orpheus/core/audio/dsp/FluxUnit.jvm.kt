package org.balch.orpheus.core.audio.dsp

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.synth.flux.FluxProcessor

/**
 * JVM Implementation of FluxUnit using JSyn.
 */
actual interface FluxUnit : AudioUnit {
    actual val clock: AudioInput
    actual val spread: AudioInput
    actual val bias: AudioInput
    actual val steps: AudioInput
    actual val dejaVu: AudioInput
    actual val length: AudioInput
    actual val rate: AudioInput
    actual val outputX1: AudioOutput
    actual val outputX3: AudioOutput
    actual val outputT2: AudioOutput
    actual val outputT1: AudioOutput
    actual val outputT3: AudioOutput
    actual fun setScale(index: Int)
}

class JsynFluxUnit : UnitGenerator(), FluxUnit {
    
    private val processor = FluxProcessor(48000f) 
    
    // JSyn Ports
    private val jsynClock = UnitInputPort("Clock")
    private val jsynSpread = UnitInputPort("Spread")
    private val jsynBias = UnitInputPort("Bias")
    private val jsynSteps = UnitInputPort("Steps")
    private val jsynDejaVu = UnitInputPort("DejaVu")
    private val jsynLength = UnitInputPort("Length")
    private val jsynRate = UnitInputPort("Rate") // Divider control
    
    // Output
    private val jsynOutput = UnitOutputPort("Output")
    private val jsynOutputX1 = UnitOutputPort("OutputX1")
    private val jsynOutputX3 = UnitOutputPort("OutputX3")
    
    // Gate Outputs
    private val jsynOutputT1 = UnitOutputPort("OutputT1")
    private val jsynOutputT2 = UnitOutputPort("OutputT2")
    private val jsynOutputT3 = UnitOutputPort("OutputT3")
    
    // AudioUnit Wrapper Ports
    override val clock: AudioInput = JsynAudioInput(jsynClock)
    override val spread: AudioInput = JsynAudioInput(jsynSpread)
    override val bias: AudioInput = JsynAudioInput(jsynBias)
    override val steps: AudioInput = JsynAudioInput(jsynSteps)
    override val dejaVu: AudioInput = JsynAudioInput(jsynDejaVu)
    override val length: AudioInput = JsynAudioInput(jsynLength)
    override val rate: AudioInput = JsynAudioInput(jsynRate)
    
    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    override val outputX1: AudioOutput = JsynAudioOutput(jsynOutputX1)
    override val outputX3: AudioOutput = JsynAudioOutput(jsynOutputX3)
    override val outputT1: AudioOutput = JsynAudioOutput(jsynOutputT1)
    override val outputT2: AudioOutput = JsynAudioOutput(jsynOutputT2)
    override val outputT3: AudioOutput = JsynAudioOutput(jsynOutputT3)
    
    // Internal state for edge detection
    private var lastClock = 0.0
    private var clockCounter = 0
    
    init {
        addPort(jsynClock)
        addPort(jsynSpread)
        addPort(jsynBias)
        addPort(jsynSteps)
        addPort(jsynDejaVu)
        addPort(jsynLength)
        addPort(jsynRate)
        addPort(jsynOutput)
        addPort(jsynOutputX1)
        addPort(jsynOutputX3)
        addPort(jsynOutputT1)
        addPort(jsynOutputT2)
        addPort(jsynOutputT3)
        
        // Default values
        jsynSpread.set(0.5)
        jsynBias.set(0.5)
        jsynSteps.set(0.5)
        jsynDejaVu.set(0.0)
        jsynLength.set(8.0)
        jsynRate.set(0.5) // Default to 1/2 note or similar
    }
    
    override fun setScale(index: Int) {
        processor.setScale(index)
    }
    
    // Generate method
    override fun generate(start: Int, end: Int) {
        val count = end - start
        if (count <= 0) return
        
        val clocks = jsynClock.values
        val spreads = jsynSpread.values
        val biases = jsynBias.values
        val steps = jsynSteps.values
        val dejaVus = jsynDejaVu.values
        val lengths = jsynLength.values
        val rates = jsynRate.values
        
        val outputs = jsynOutput.values
        val outputsX1 = jsynOutputX1.values
        val outputsX3 = jsynOutputX3.values
        val outputsT1 = jsynOutputT1.values
        val outputsT2 = jsynOutputT2.values
        val outputsT3 = jsynOutputT3.values
        
        for (i in 0 until count) {
            val idx = start + i
            val currentClock = clocks[idx]
            
            // Rising edge detection
            if (currentClock > 0.1 && lastClock <= 0.1) {
                // Determine divisor based on rate input (0.0 - 1.0)
                // Input is 24 PPQN
                val rateVal = rates[idx]
                val divisor = when {
                    rateVal < 0.15 -> 6   // 1/16th (Fast)
                    rateVal < 0.30 -> 12  // 1/8th
                    rateVal < 0.50 -> 24  // 1/4th (Beat)
                    rateVal < 0.70 -> 48  // 1/2th
                    rateVal < 0.85 -> 96  // 1 Bar
                    else -> 192           // 2 Bars (Slow)
                }
                
                clockCounter++
                if (clockCounter >= divisor) {
                    clockCounter = 0 // Reset counter
                    
                    // Trigger Processor
                    processor.setSpread(spreads[idx].toFloat())
                    processor.setBias(biases[idx].toFloat())
                    processor.setSteps(steps[idx].toFloat())
                    processor.setDejaVu(dejaVus[idx].toFloat())
                    processor.setLength(lengths[idx].toInt())
                    
                    processor.tick()
                }
            } else if (currentClock <= 0.1) {
                // Ensure gates turn off when clock is low (simplified pulse width)
                // For proper gates we might want a counter, but clock pulse is usually short.
                // We use tickClockOff to allow TimingGenerator to reset its state if needed.
                if (lastClock > 0.1) {
                     processor.tickClockOff()
                }
            }
            
            lastClock = currentClock
            outputs[idx] = processor.getX2().toDouble() // Main (X2)
            outputsX1[idx] = processor.getX1().toDouble() // Secondary (X1)
            outputsX3[idx] = processor.getX3().toDouble() // Tertiary (X3)
            
            outputsT1[idx] = processor.getT1().toDouble()
            outputsT2[idx] = processor.getT2().toDouble() // Main Clock Gate
            outputsT3[idx] = processor.getT3().toDouble()
        }
    }
}
