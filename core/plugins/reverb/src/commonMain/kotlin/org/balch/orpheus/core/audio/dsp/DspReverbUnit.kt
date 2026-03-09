package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.reverb.DattorroReverb

/**
 * Dattorro plate reverb DSP unit — delegates to the shared [DattorroReverb] engine.
 *
 * Delay line lengths are scaled from the 48kHz reference to [dspSampleRate].
 */
class DspReverbUnit : ReverbUnit, DspProcessable {
    override var enabled = true

    private val reverb = DattorroReverb(dspSampleRate)
    private var bypass = false

    // --- Audio ports ---
    private val dspInputL = DspAudioInput("Reverb.inL")
    private val dspInputR = DspAudioInput("Reverb.inR")
    private val dspOutput = DspAudioOutput("Reverb.outL")
    private val dspOutputR = DspAudioOutput("Reverb.outR")

    override val inputLeft: AudioInput = dspInputL
    override val inputRight: AudioInput = dspInputR
    override val output: AudioOutput = dspOutput
    override val outputRight: AudioOutput = dspOutputR

    // --- ReverbUnit interface ---

    override fun setAmount(amount: Float) { reverb.amount = amount }
    override fun setTime(time: Float) { reverb.reverbTime = time }
    override fun setDiffusion(diffusion: Float) { reverb.diffusion = diffusion }
    override fun setLp(lp: Float) { reverb.lp = lp }
    override fun setInputGain(gain: Float) { reverb.inputGain = gain }
    override fun setBypass(bypass: Boolean) { this.bypass = bypass }

    override fun clear() { reverb.clear() }

    // --- DspProcessable interface ---

    override fun process(numFrames: Int) {
        val outL = dspOutput.getBuffer()
        val outR = dspOutputR.getBuffer()

        if (bypass) {
            outL.fill(0f, 0, numFrames)
            outR.fill(0f, 0, numFrames)
            return
        }

        val inL = dspInputL.getBuffer()
        val inR = dspInputR.getBuffer()

        for (i in 0 until numFrames) {
            reverb.process(inL[i], inR[i])
            outL[i] = reverb.outLeft
            outR[i] = reverb.outRight
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspInputL.allocate(maxFrames)
        dspInputR.allocate(maxFrames)
        dspOutput.allocate(maxFrames)
        dspOutputR.allocate(maxFrames)
    }

    private val inputPorts = listOf(dspInputL, dspInputR)
    private val outputPorts = listOf(dspOutput, dspOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
