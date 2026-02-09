package org.balch.orpheus.plugins.reverb

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.JsynAudioInput
import org.balch.orpheus.core.audio.dsp.JsynAudioOutput
import org.balch.orpheus.core.audio.dsp.ReverbUnit

class JsynReverbUnit : UnitGenerator(), ReverbUnit {

    private val reverb = DattorroReverb()

    // JSyn ports
    private val jsynInputL = UnitInputPort("InputLeft")
    private val jsynInputR = UnitInputPort("InputRight")
    private val jsynOutputL = UnitOutputPort("OutputLeft")
    private val jsynOutputR = UnitOutputPort("OutputRight")

    override val inputLeft: AudioInput = JsynAudioInput(jsynInputL)
    override val inputRight: AudioInput = JsynAudioInput(jsynInputR)
    override val output: AudioOutput = JsynAudioOutput(jsynOutputL)
    override val outputRight: AudioOutput = JsynAudioOutput(jsynOutputR)

    init {
        addPort(jsynInputL)
        addPort(jsynInputR)
        addPort(jsynOutputL)
        addPort(jsynOutputR)
    }

    override fun setAmount(amount: Float) { reverb.amount = amount }
    override fun setTime(time: Float) { reverb.reverbTime = time }
    override fun setDiffusion(diffusion: Float) { reverb.diffusion = diffusion }
    override fun setLp(lp: Float) { reverb.lp = lp }
    override fun setInputGain(gain: Float) { reverb.inputGain = gain }
    override fun clear() { reverb.clear() }

    override fun generate(start: Int, end: Int) {
        val inL = jsynInputL.values
        val inR = jsynInputR.values
        val outL = jsynOutputL.values
        val outR = jsynOutputR.values

        for (i in start until end) {
            reverb.process(inL[i].toFloat(), inR[i].toFloat())
            outL[i] = reverb.outLeft.toDouble()
            outR[i] = reverb.outRight.toDouble()
        }
    }
}
