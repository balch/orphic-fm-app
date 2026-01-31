package org.balch.orpheus.plugins.resonator

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.JsynAudioInput
import org.balch.orpheus.core.audio.dsp.JsynAudioOutput
import org.balch.orpheus.core.audio.dsp.ResonatorUnit
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.resonator.engine.ModalResonator
import org.balch.orpheus.plugins.resonator.engine.ResonatorString

class JsynResonatorUnit : UnitGenerator(), ResonatorUnit {
    
    // Modal resonator (64 SVF filters)  
    private val modalResonator = ModalResonator(maxModes = 24) // Reduced for CPU
    
    // String resonator (Karplus-Strong)
    private val stringResonator = ResonatorString()
    
    // JSyn ports
    private val jsynInput = UnitInputPort("Input")
    private val jsynOutput = UnitOutputPort("Output")
    private val jsynAuxOutput = UnitOutputPort("AuxOutput")
    
    override val input: AudioInput = JsynAudioInput(jsynInput)
    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    override val auxOutput: AudioOutput = JsynAudioOutput(jsynAuxOutput)
    
    // State
    private var enabled = false
    private var mode = 0 // 0=Modal, 1=String, 2=Sympathetic
    private var structure = 0.25f
    private var brightness = 0.5f
    private var damping = 0.3f
    private var position = 0.5f
    
    // Strum trigger
    private var strumPending = false
    private var strumFrequency = 220f
    
    init {
        modalResonator.init()
        stringResonator.init()
        
        addPort(jsynInput)
        addPort(jsynOutput)
        addPort(jsynAuxOutput)
    }
    
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
    
    override fun setMode(mode: Int) {
        this.mode = mode.coerceIn(0, 2)
    }
    
    override fun setStructure(value: Float) {
        structure = value.coerceIn(0f, 1f)
        modalResonator.structure = structure
        // For string mode, structure maps to dispersion
    }
    
    override fun setBrightness(value: Float) {
        brightness = value.coerceIn(0f, 1f)
        modalResonator.brightness = brightness
        stringResonator.brightness = brightness
    }
    
    override fun setDamping(value: Float) {
        damping = value.coerceIn(0f, 1f)
        modalResonator.damping = damping
        stringResonator.damping = damping
    }
    
    override fun setPosition(value: Float) {
        position = value.coerceIn(0f, 1f)
        modalResonator.position = position
        stringResonator.position = position
    }
    
    override fun strum(frequency: Float) {
        strumFrequency = frequency.coerceIn(20f, 20000f)
        strumPending = true
    }
    
    override fun generate(start: Int, end: Int) {
        val inputs = jsynInput.values
        val outputs = jsynOutput.values
        val auxOutputs = jsynAuxOutput.values
        
        for (i in start until end) {
            val inputSample = inputs[i].toFloat()
            
            // Handle strum trigger
            val excitation = if (i == start && strumPending) {
                strumPending = false
                // Set frequency for both resonators
                val normalizedFreq = strumFrequency / SynthDsp.SAMPLE_RATE
                modalResonator.frequency = normalizedFreq
                stringResonator.frequency = normalizedFreq
                // Impulse excitation
                1.0f
            } else {
                inputSample
            }
            
            if (!enabled) {
                // Bypass: pass through input
                outputs[i] = inputSample.toDouble()
                auxOutputs[i] = 0.0
                continue
            }
            
            when (mode) {
                0 -> { // Modal
                    val (odd, even) = modalResonator.process(excitation)
                    outputs[i] = odd.toDouble()
                    auxOutputs[i] = even.toDouble()
                }
                1 -> { // String
                    val (main, aux) = stringResonator.process(excitation)
                    outputs[i] = main.toDouble()
                    auxOutputs[i] = aux.toDouble()
                }
                2 -> { // Sympathetic (uses both)
                    // Process through modal first, then string
                    val (modalOdd, modalEven) = modalResonator.process(excitation)
                    val (stringMain, _) = stringResonator.process(modalOdd)
                    outputs[i] = stringMain.toDouble()
                    auxOutputs[i] = modalEven.toDouble()
                }
                else -> {
                    outputs[i] = inputSample.toDouble()
                    auxOutputs[i] = 0.0
                }
            }
        }
    }
}
