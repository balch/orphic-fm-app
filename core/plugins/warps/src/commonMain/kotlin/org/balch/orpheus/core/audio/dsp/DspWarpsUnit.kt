package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.warps.engine.WarpsProcessor

/**
 * DSP WarpsUnit — delegates to the real [WarpsProcessor] engine.
 *
 * Parameter inputs (algorithm, timbre, level1, level2) are read once per block
 * (first sample) and forwarded to the processor's [WarpsParameters]. The
 * processor handles its own internal parameter smoothing and algorithm
 * cross-fading.
 */
class DspWarpsUnit : WarpsUnit, DspProcessable {
    override var enabled = true

    private val processor = WarpsProcessor()
    private var bypass = false

    // Conversion buffers for processor I/O (resized in allocateBuffers)
    private var leftInBuffer = FloatArray(256)
    private var rightInBuffer = FloatArray(256)
    private var leftOutBuffer = FloatArray(256)
    private var rightOutBuffer = FloatArray(256)

    // --- Audio ports ---
    private val dspInputL = DspAudioInput("Warps.carrier")
    private val dspInputR = DspAudioInput("Warps.modulator")
    private val dspAlgorithm = DspAudioInput("Warps.algorithm")
    private val dspTimbre = DspAudioInput("Warps.timbre")
    private val dspLevel1 = DspAudioInput("Warps.level1")
    private val dspLevel2 = DspAudioInput("Warps.level2")
    private val dspOutput = DspAudioOutput("Warps.outL")
    private val dspOutputR = DspAudioOutput("Warps.outR")

    override val inputLeft: AudioInput = dspInputL
    override val inputRight: AudioInput = dspInputR
    override val algorithm: AudioInput = dspAlgorithm
    override val timbre: AudioInput = dspTimbre
    override val level1: AudioInput = dspLevel1
    override val level2: AudioInput = dspLevel2
    override val output: AudioOutput = dspOutput
    override val outputRight: AudioOutput = dspOutputR

    init {
        processor.init(dspSampleRate)
    }

    override fun setBypass(bypass: Boolean) {
        this.bypass = bypass
    }

    override fun process(numFrames: Int) {
        val outL = dspOutput.getBuffer()
        val outR = dspOutputR.getBuffer()

        if (bypass) {
            for (i in 0 until numFrames) { outL[i] = 0f; outR[i] = 0f }
            return
        }

        // Copy input port buffers into processor input arrays
        val inL = dspInputL.getBuffer()
        val inR = dspInputR.getBuffer()
        inL.copyInto(leftInBuffer, 0, 0, numFrames)
        inR.copyInto(rightInBuffer, 0, 0, numFrames)

        // Read parameter values from first sample of each control port
        val p = processor.parameters
        p.modulationAlgorithm = dspAlgorithm.getBuffer()[0]
        p.modulationParameter = dspTimbre.getBuffer()[0]
        p.channelDrive[0] = dspLevel1.getBuffer()[0]
        p.channelDrive[1] = dspLevel2.getBuffer()[0]

        // Run the Warps processor
        processor.process(leftInBuffer, rightInBuffer, leftOutBuffer, rightOutBuffer, numFrames)

        // Copy processor output into output port buffers
        leftOutBuffer.copyInto(outL, 0, 0, numFrames)
        rightOutBuffer.copyInto(outR, 0, 0, numFrames)
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspInputL.allocate(maxFrames)
        dspInputR.allocate(maxFrames)
        dspAlgorithm.allocate(maxFrames)
        dspTimbre.allocate(maxFrames)
        dspLevel1.allocate(maxFrames)
        dspLevel2.allocate(maxFrames)
        dspOutput.allocate(maxFrames)
        dspOutputR.allocate(maxFrames)

        // Pre-size conversion buffers to match
        if (leftInBuffer.size < maxFrames) {
            leftInBuffer = FloatArray(maxFrames)
            rightInBuffer = FloatArray(maxFrames)
            leftOutBuffer = FloatArray(maxFrames)
            rightOutBuffer = FloatArray(maxFrames)
        }
    }

    private val inputPorts = listOf(dspInputL, dspInputR, dspAlgorithm, dspTimbre, dspLevel1, dspLevel2)
    private val outputPorts = listOf(dspOutput, dspOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
