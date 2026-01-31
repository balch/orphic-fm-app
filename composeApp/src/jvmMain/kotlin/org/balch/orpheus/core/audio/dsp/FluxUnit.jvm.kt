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
    
    // AudioUnit Wrapper Ports
    override val clock: AudioInput = JsynAudioInput(jsynClock)
    override val spread: AudioInput = JsynAudioInput(jsynSpread)
    override val bias: AudioInput = JsynAudioInput(jsynBias)
    override val steps: AudioInput = JsynAudioInput(jsynSteps)
    override val dejaVu: AudioInput = JsynAudioInput(jsynDejaVu)
    override val length: AudioInput = JsynAudioInput(jsynLength)
    override val rate: AudioInput = JsynAudioInput(jsynRate)
    
    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    
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
        
        for (i in 0 until count) {
            val idx = start + i
            val currentClock = clocks[idx]
            
            // Rising edge detection
            if (currentClock > 0.1 && lastClock <= 0.1) {
                // Determine divisor based on rate input (0.0 - 1.0)
                // Input is 24 PPQN
                // 1/16th = 6 ticks
                // 1/8th = 12 ticks
                // 1/4th = 24 ticks
                // 1/2th = 48 ticks
                // 1 Bar = 96 ticks
                // 2 Bar = 192 ticks
                
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
            }
            
            lastClock = currentClock
            outputs[idx] = processor.getCurrentVoltage().toDouble()
        }
    }
}
