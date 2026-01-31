package org.balch.orpheus.core.audio.dsp

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.synth.AnalogBassDrum
import org.balch.orpheus.core.audio.dsp.synth.AnalogSnareDrum
import org.balch.orpheus.core.audio.dsp.synth.FmDrum
import org.balch.orpheus.core.audio.dsp.synth.MetallicHiHat
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlin.math.tanh

/**
 * JVM Implementation of DrumUnit using JSyn.
 * 
 * Fixed to render ALL drums simultaneously (not just the last triggered type).
 * This prevents clicks when rapidly triggering different drums.
 */
actual interface DrumUnit : AudioUnit {
    actual fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    )
    
    actual fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    )

    actual fun trigger(type: Int, accent: Float)
    
    actual val triggerInputBd: AudioInput
    actual val triggerInputSd: AudioInput
    actual val triggerInputHh: AudioInput
}

class JsynDrumUnit : UnitGenerator(), DrumUnit {
    private val bd = AnalogBassDrum()
    private val sd = AnalogSnareDrum()
    private val hh = MetallicHiHat()
    private val fm = FmDrum(SynthDsp.SAMPLE_RATE)
    
    // JSyn output port
    private val jsynOutput = UnitOutputPort("Output")
    
    // JSyn Trigger Inputs
    private val jsynTriggerInputBd = UnitInputPort("TriggerBD")
    private val jsynTriggerInputSd = UnitInputPort("TriggerSD")
    private val jsynTriggerInputHh = UnitInputPort("TriggerHH")
    
    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    
    // AudioUnit Wrapper Ports
    override val triggerInputBd: AudioInput = JsynAudioInput(jsynTriggerInputBd)
    override val triggerInputSd: AudioInput = JsynAudioInput(jsynTriggerInputSd)
    override val triggerInputHh: AudioInput = JsynAudioInput(jsynTriggerInputHh)

    // Mode: 0 = 808, 1 = FM
    private var drumMode = 0
    private var pendingMode = 0

    // Per-drum trigger flags
    private var bdTrigger = false
    private var sdTrigger = false
    private var hhTrigger = false
    private var fmTrigger = false
    
    // Per-drum parameters (stored on trigger, used until next trigger)
    // Bass Drum
    private var bdAccent = 0.5f
    private var bdF0 = 55.0f / SynthDsp.SAMPLE_RATE
    private var bdTone = 0.5f
    private var bdDecay = 0.5f
    private var bdP4 = 0.5f  // Attack FM
    private var bdP5 = 0.5f  // Self FM
    
    // Snare Drum
    private var sdAccent = 0.5f
    private var sdF0 = 180.0f / SynthDsp.SAMPLE_RATE
    private var sdTone = 0.5f
    private var sdDecay = 0.5f
    private var sdP4 = 0.5f  // Snappiness
    
    // Hi-Hat
    private var hhAccent = 0.5f
    private var hhF0 = 400.0f / SynthDsp.SAMPLE_RATE
    private var hhTone = 0.5f
    private var hhDecay = 0.5f
    private var hhP4 = 0.5f  // Noisiness

    init {
        bd.init()
        sd.init()
        hh.init()
        fm.init()
        
        // Add JSyn output port
        addPort(jsynOutput)
        addPort(jsynTriggerInputBd)
        addPort(jsynTriggerInputSd)
        addPort(jsynTriggerInputHh)
    }

    override fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    ) {
        setParameters(type, frequency, tone, decay, param4, param5)
        trigger(type, accent)
    }

    override fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    ) {
        val f0 = frequency / SynthDsp.SAMPLE_RATE
        
        when (type) {
            0 -> { // Bass Drum
                bdF0 = f0
                bdTone = tone
                bdDecay = decay
                bdP4 = param4
                bdP5 = param5
            }
            1 -> { // Snare Drum
                sdF0 = f0
                sdTone = tone
                sdDecay = decay
                sdP4 = param4
            }
            2 -> { // Hi-Hat
                hhF0 = f0
                hhTone = tone
                hhDecay = decay
                hhP4 = param4
            }
        }
    }

    override fun trigger(type: Int, accent: Float) {
        if (type >= 10 || drumMode == 1) {
             // For FM mode, accent modulates FM drum
             // Ideally we'd map "fmAccent" or similar
             bdAccent = accent // using BD accent for FM for now as shared param
             fmTrigger = true
             return
        }

        when (type) {
            0 -> {
                bdAccent = accent
                bdTrigger = true
            }
            1 -> {
                sdAccent = accent
                sdTrigger = true
            }
            2 -> {
                hhAccent = accent
                hhTrigger = true
            }
        }
    }
    
    // Internal state for edge detection
    private var lastBdTrig = 0.0
    private var lastSdTrig = 0.0
    private var lastHhTrig = 0.0

    override fun generate(start: Int, end: Int) {
        val outputs = jsynOutput.values
        val bdTrigs = jsynTriggerInputBd.values
        val sdTrigs = jsynTriggerInputSd.values
        val hhTrigs = jsynTriggerInputHh.values
        
        for (i in start until end) {
            // Manual Triggers (Control Rate, processed at buffer start)
            val bdManual = if (i == start && bdTrigger) { bdTrigger = false; true } else false
            val sdManual = if (i == start && sdTrigger) { sdTrigger = false; true } else false
            val hhManual = if (i == start && hhTrigger) { hhTrigger = false; true } else false
            val fmManual = if (i == start && fmTrigger) { fmTrigger = false; true } else false
            
            // Audio Rate Triggers (Edge Detection)
            val bdIn = bdTrigs[i - start] // JSyn unit buffers are usually aligned
            val sdIn = sdTrigs[i - start]
            val hhIn = hhTrigs[i - start]
            
            val bdAudio = bdIn > 0.1 && lastBdTrig <= 0.1
            val sdAudio = sdIn > 0.1 && lastSdTrig <= 0.1
            val hhAudio = hhIn > 0.1 && lastHhTrig <= 0.1
            
            lastBdTrig = bdIn
            lastSdTrig = sdIn
            lastHhTrig = hhIn
            
            // Combine Triggers
            val doBd = bdManual || bdAudio
            val doSd = sdManual || sdAudio
            val doHh = hhManual || hhAudio
            val doFm = fmManual
            
            // Process ALL drums every sample (they naturally decay to 0 when not triggered)
            val bdSample = bd.process(doBd, bdAccent, bdF0, bdTone, bdDecay, bdP4, bdP5)
            val sdSample = sd.process(doSd, sdAccent, sdF0, sdTone, sdDecay, sdP4)
            val hhSample = hh.process(doHh, hhAccent, hhF0, hhTone, hhDecay, hhP4)
            
            // FM Drum - using BD params as proxy for the single FM instance for now
            val fmSample = fm.process(doFm, bdAccent, bdF0, bdTone, bdDecay, bdP4, bdP5)
            
            // Mix all drums with gain staging
            // BD is internally scaled 0.3x so needs higher mix, SD/HH have stronger output
            var mix = bdSample * 1.2f + sdSample * 0.6f + hhSample * 0.5f
            if (drumMode == 1) {
                mix = fmSample * 0.8f
            } else {
                mix += fmSample * 0.2f // Allow subtle FM mix in 808 mode?
            }
            
            // Soft limiter (tanh saturation) - prevents hard clipping
            outputs[i] = softLimit(mix).toDouble()
        }
    }
    
    /**
     * Soft saturation curve to prevent hard clipping.
     * Linear below 0.5, tanh saturation above.
     */
    private fun softLimit(x: Float): Float {
        return if (x.absoluteValue < 0.5f) {
            x
        } else {
            sign(x) * (0.5f + 0.5f * tanh((x.absoluteValue - 0.5f) * 2f))
        }
    }
}
