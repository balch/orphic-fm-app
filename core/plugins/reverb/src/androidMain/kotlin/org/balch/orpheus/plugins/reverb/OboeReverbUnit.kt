package org.balch.orpheus.plugins.reverb

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DspAudioInput
import org.balch.orpheus.core.audio.dsp.DspAudioOutput
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.ReverbUnit
import kotlin.concurrent.Volatile

class OboeReverbUnit : ReverbUnit, DspProcessable {
    @Volatile override var enabled = true

    private val reverb = DattorroReverb()
    @Volatile private var bypass = false

    // Ports
    private val oboeInputL = DspAudioInput("InputLeft")
    private val oboeInputR = DspAudioInput("InputRight")
    private val oboeOutputL = DspAudioOutput("OutputLeft")
    private val oboeOutputR = DspAudioOutput("OutputRight")

    override val inputLeft: AudioInput = oboeInputL
    override val inputRight: AudioInput = oboeInputR
    override val output: AudioOutput = oboeOutputL
    override val outputRight: AudioOutput = oboeOutputR

    override fun setAmount(amount: Float) { reverb.amount = amount }
    override fun setTime(time: Float) { reverb.reverbTime = time }
    override fun setDiffusion(diffusion: Float) { reverb.diffusion = diffusion }
    override fun setLp(lp: Float) { reverb.lp = lp }
    override fun setInputGain(gain: Float) { reverb.inputGain = gain }
    override fun clear() { reverb.clear() }
    override fun setBypass(bypass: Boolean) { this.bypass = bypass }

    override fun process(numFrames: Int) {
        val outL = oboeOutputL.getBuffer()
        val outR = oboeOutputR.getBuffer()

        if (bypass) {
            outL.fill(0f, 0, numFrames)
            outR.fill(0f, 0, numFrames)
            return
        }

        val inL = oboeInputL.getBuffer()
        val inR = oboeInputR.getBuffer()

        for (i in 0 until numFrames) {
            reverb.process(inL[i], inR[i])
            outL[i] = reverb.outLeft
            outR[i] = reverb.outRight
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeInputL.allocate(maxFrames)
        oboeInputR.allocate(maxFrames)
        oboeOutputL.allocate(maxFrames)
        oboeOutputR.allocate(maxFrames)
    }

    private val inputPorts = listOf(oboeInputL, oboeInputR)
    private val outputPorts = listOf(oboeOutputL, oboeOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
