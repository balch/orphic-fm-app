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
    actual fun setScale(index: Int)
}

class JsynFluxUnit : UnitGenerator(), FluxUnit {
    
    // The core logic
    // We pass a dummy sample rate since the processor Logic might depend on it, 
    // although FluxProcessor currently ignores it.
    private val processor = FluxProcessor(48000f) 
    
    // JSyn Ports
    private val jsynClock = UnitInputPort("Clock")
    private val jsynSpread = UnitInputPort("Spread")
    private val jsynBias = UnitInputPort("Bias")
    private val jsynSteps = UnitInputPort("Steps")
    private val jsynDejaVu = UnitInputPort("DejaVu")
    private val jsynLength = UnitInputPort("Length") // Loop length
    
    // Output
    private val jsynOutput = UnitOutputPort("Output")
    
    // AudioUnit Wrapper Ports
    override val clock: AudioInput = JsynAudioInput(jsynClock)
    override val spread: AudioInput = JsynAudioInput(jsynSpread)
    override val bias: AudioInput = JsynAudioInput(jsynBias)
    override val steps: AudioInput = JsynAudioInput(jsynSteps)
    override val dejaVu: AudioInput = JsynAudioInput(jsynDejaVu)
    override val length: AudioInput = JsynAudioInput(jsynLength)
    
    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    
    // Internal state for edge detection
    private var lastClock = 0.0
    
    init {
        addPort(jsynClock)
        addPort(jsynSpread)
        addPort(jsynBias)
        addPort(jsynSteps)
        addPort(jsynDejaVu)
        addPort(jsynLength)
        addPort(jsynOutput)
        
        // Default values
        jsynSpread.set(0.5)
        jsynBias.set(0.5)
        jsynSteps.set(0.5)
        jsynDejaVu.set(0.0)
        jsynLength.set(8.0)
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
        
        val outputs = jsynOutput.values
        
        // To save CPU, we could sample parameters once per block if they don't change fast.
        // However, for sample accuracy and since FluxProcessor is light, we can act on triggers.
        // We'll update parameters only when a trigger occurs to ensure the new value reflects current state.
        
        for (i in 0 until count) {
            val idx = start + i
            val currentClock = clocks[idx]
            
            // Rising edge detection (Schmitt trigger style or simple threshold)
            if (currentClock > 0.1 && lastClock <= 0.1) {
                // Triggered!
                
                // Update parameters
                processor.setSpread(spreads[idx].toFloat())
                processor.setBias(biases[idx].toFloat())
                processor.setSteps(steps[idx].toFloat())
                processor.setDejaVu(dejaVus[idx].toFloat())
                processor.setLength(lengths[idx].toInt())
                
                // Generate new voltage
                processor.tick()
            }
            
            lastClock = currentClock
            
            // Output the current voltage (sample and hold behavior between ticks)
            outputs[idx] = processor.getCurrentVoltage().toDouble()
        }
    }
}
