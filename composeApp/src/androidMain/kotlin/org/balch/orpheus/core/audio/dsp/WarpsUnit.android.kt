package org.balch.orpheus.core.audio.dsp

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.synth.warps.WarpsProcessor



class JsynWarpsUnit : UnitGenerator(), WarpsUnit {
    
    private val processor = WarpsProcessor()
    
    // Audio Ports
    private val jsynInputLeft = UnitInputPort("InputLeft")
    private val jsynInputRight = UnitInputPort("InputRight")
    private val jsynOutputLeft = UnitOutputPort("OutputLeft")
    private val jsynOutputRight = UnitOutputPort("OutputRight")
    
    // Parameter Ports
    private val jsynAlgorithm = UnitInputPort("Algorithm")
    private val jsynTimbre = UnitInputPort("Timbre")
    private val jsynLevel1 = UnitInputPort("Level1")
    private val jsynLevel2 = UnitInputPort("Level2")
    
    // AudioUnit Interface Implementation
    override val output: AudioOutput = JsynAudioOutput(jsynOutputLeft)
    override val outputRight: AudioOutput = JsynAudioOutput(jsynOutputRight)
    
    override val inputLeft: AudioInput = JsynAudioInput(jsynInputLeft)
    override val inputRight: AudioInput = JsynAudioInput(jsynInputRight)
    
    override val algorithm: AudioInput = JsynAudioInput(jsynAlgorithm)
    override val timbre: AudioInput = JsynAudioInput(jsynTimbre)
    override val level1: AudioInput = JsynAudioInput(jsynLevel1)
    override val level2: AudioInput = JsynAudioInput(jsynLevel2)
    
    // Buffers for block processing
    private var leftInBuffer = FloatArray(256)
    private var rightInBuffer = FloatArray(256)
    private var leftOutBuffer = FloatArray(256)
    private var rightOutBuffer = FloatArray(256)
    
    init {
        processor.init(48000f) // Will be updated if needed or use default
        
        jsynAlgorithm.set(0.0)
        jsynTimbre.set(0.5)
        jsynLevel1.set(0.5)
        jsynLevel2.set(0.0)
        
        addPort(jsynInputLeft)
        addPort(jsynInputRight)
        addPort(jsynOutputLeft)
        addPort(jsynOutputRight)
        addPort(jsynAlgorithm)
        addPort(jsynTimbre)
        addPort(jsynLevel1)
        addPort(jsynLevel2)
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
        p.modulationAlgorithm = jsynAlgorithm.getValue(start).toFloat()
        p.modulationParameter = jsynTimbre.getValue(start).toFloat()
        
        p.channelDrive[0] = jsynLevel1.getValue(start).toFloat()
        p.channelDrive[1] = jsynLevel2.getValue(start).toFloat()
        
        processor.process(leftInBuffer, rightInBuffer, leftOutBuffer, rightOutBuffer, count)
        
        val outL = jsynOutputLeft.values
        val outR = jsynOutputRight.values
        
        for (i in 0 until count) {
            outL[start + i] = leftOutBuffer[i].toDouble()
            outR[start + i] = rightOutBuffer[i].toDouble()
        }
    }
}
