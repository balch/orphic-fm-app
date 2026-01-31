package org.balch.orpheus.core.audio.dsp

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.synth.grains.GrainsMode
import org.balch.orpheus.core.audio.dsp.synth.grains.GranularProcessor

/**
 * Android Implementation of GrainsUnit using JSyn.
 */


class JsynGrainsUnit : UnitGenerator(), GrainsUnit {
    
    private val processor = GranularProcessor()
    
    // Audio Ports
    private val jsynInputLeft = UnitInputPort("InputLeft")
    private val jsynInputRight = UnitInputPort("InputRight")
    private val jsynOutputLeft = UnitOutputPort("OutputLeft")
    private val jsynOutputRight = UnitOutputPort("OutputRight")
    
    // Parameter Ports
    private val jsynPosition = UnitInputPort("Position")
    private val jsynSize = UnitInputPort("Size")
    private val jsynPitch = UnitInputPort("Pitch")
    private val jsynDensity = UnitInputPort("Density")
    private val jsynTexture = UnitInputPort("Texture")
    private val jsynDryWet = UnitInputPort("DryWet")
    private val jsynFreeze = UnitInputPort("Freeze")
    private val jsynTrigger = UnitInputPort("Trigger")
    
    // AudioUnit Interface Implementation
    override val output: AudioOutput = JsynAudioOutput(jsynOutputLeft)
    override val outputRight: AudioOutput = JsynAudioOutput(jsynOutputRight)
    
    override val inputLeft: AudioInput = JsynAudioInput(jsynInputLeft)
    override val inputRight: AudioInput = JsynAudioInput(jsynInputRight)
    
    override val position: AudioInput = JsynAudioInput(jsynPosition)
    override val size: AudioInput = JsynAudioInput(jsynSize)
    override val pitch: AudioInput = JsynAudioInput(jsynPitch)
    override val density: AudioInput = JsynAudioInput(jsynDensity)
    override val texture: AudioInput = JsynAudioInput(jsynTexture)
    override val dryWet: AudioInput = JsynAudioInput(jsynDryWet)
    override val freeze: AudioInput = JsynAudioInput(jsynFreeze)
    override val trigger: AudioInput = JsynAudioInput(jsynTrigger)
    
    private var mode = 2 // Default Looping Delay
    
    // Buffers for block processing
    private var leftInBuffer = FloatArray(256)
    private var rightInBuffer = FloatArray(256)
    private var leftOutBuffer = FloatArray(256)
    private var rightOutBuffer = FloatArray(256)
    
    // Edge detection state
    private var lastTrigState = false
    
    init {
        processor.init(2)
        jsynPosition.set(0.0)
        jsynSize.set(0.0)
        jsynPitch.set(0.0)
        jsynDensity.set(0.0)
        jsynTexture.set(0.0)
        jsynDryWet.set(0.5)
        jsynFreeze.set(0.0)
        jsynTrigger.set(0.0)
        
        addPort(jsynInputLeft)
        addPort(jsynInputRight)
        addPort(jsynOutputLeft)
        addPort(jsynOutputRight)
        addPort(jsynPosition)
        addPort(jsynSize)
        addPort(jsynPitch)
        addPort(jsynDensity)
        addPort(jsynTexture)
        addPort(jsynDryWet)
        addPort(jsynFreeze)
        addPort(jsynTrigger)
    }

    override fun setMode(mode: Int) {
        this.mode = mode
        // Update processor parameters mode to enable mode switching
        processor.parameters.mode = when (mode) {
            0 -> GrainsMode.GRANULAR
            1 -> GrainsMode.LOOPING_DELAY
            2 -> GrainsMode.SHIMMER
            else -> GrainsMode.GRANULAR
        }
    }

    override fun generate(start: Int, end: Int) {
        val count = end - start
        if (count <= 0) return
        
        if (leftInBuffer.size < count) {
            leftInBuffer = FloatArray(count)
            rightInBuffer = FloatArray(count)
            leftOutBuffer = FloatArray(count)
            rightOutBuffer = FloatArray(count)
        }
        
        val inL = jsynInputLeft.values
        val inR = jsynInputRight.values
        
        for (i in 0 until count) {
            leftInBuffer[i] = inL[start + i].toFloat()
            rightInBuffer[i] = inR[start + i].toFloat()
        }
        
        val p = processor.parameters
        p.position = jsynPosition.getValue(start).toFloat()
        p.size = jsynSize.getValue(start).toFloat()
        p.pitch = jsynPitch.getValue(start).toFloat()
        p.density = jsynDensity.getValue(start).toFloat()
        // Feedback: In original Clouds, this is a separate BLEND parameter (0-100%)
        // Since we don't have a dedicated feedback knob, set to 0 for cleaner sound
        // Could add a feedback knob to UI later if desired
        p.feedback = 0f
        p.texture = jsynTexture.getValue(start).toFloat()
        p.dryWet = jsynDryWet.getValue(start).toFloat()
        
        val freezeVal = jsynFreeze.getValue(start)
        p.freeze = freezeVal > 0.5
        
        val trigVal = jsynTrigger.getValue(start)
        val currentTrig = trigVal > 0.5
        if (currentTrig && !lastTrigState) {
            p.trigger = true
        } else {
            p.trigger = false
        }
        lastTrigState = currentTrig
        
        processor.process(leftInBuffer, rightInBuffer, leftOutBuffer, rightOutBuffer, count)
        
        val outL = jsynOutputLeft.values
        val outR = jsynOutputRight.values
        
        for (i in 0 until count) {
            outL[start + i] = leftOutBuffer[i].toDouble()
            outR[start + i] = rightOutBuffer[i].toDouble()
        }
    }
}
