package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.grains.engine.GrainsMode
import org.balch.orpheus.plugins.grains.engine.GranularProcessor

/**
 * DSP GrainsUnit — delegates to the real [GranularProcessor] engine.
 *
 * Parameter inputs (position, size, pitch, density, texture, dryWet, freeze,
 * trigger) are read once per block (first sample) and forwarded to the
 * processor's [GrainsParameters]. The processor handles its own internal
 * smoothing, feedback, dry/wet mixing, and mode-specific playback.
 */
class DspGrainsUnit : GrainsUnit, DspProcessable {
    override var enabled = true

    private val processor = GranularProcessor()
    private var bypass = false
    private var lastTrigState = false

    // Conversion buffers for processor I/O (resized in allocateBuffers)
    private var leftInBuffer = FloatArray(256)
    private var rightInBuffer = FloatArray(256)
    private var leftOutBuffer = FloatArray(256)
    private var rightOutBuffer = FloatArray(256)

    // --- Audio ports ---
    private val dspInputL = DspAudioInput("Grains.inL")
    private val dspInputR = DspAudioInput("Grains.inR")
    private val dspPosition = DspAudioInput("Grains.position")
    private val dspSize = DspAudioInput("Grains.size")
    private val dspPitch = DspAudioInput("Grains.pitch")
    private val dspDensity = DspAudioInput("Grains.density")
    private val dspTexture = DspAudioInput("Grains.texture")
    private val dspDryWet = DspAudioInput("Grains.dryWet")
    private val dspFreeze = DspAudioInput("Grains.freeze")
    private val dspTrigger = DspAudioInput("Grains.trigger")
    private val dspFeedback = DspAudioInput("Grains.feedback")
    private val dspOutput = DspAudioOutput("Grains.outL")
    private val dspOutputR = DspAudioOutput("Grains.outR")

    override val inputLeft: AudioInput = dspInputL
    override val inputRight: AudioInput = dspInputR
    override val position: AudioInput = dspPosition
    override val size: AudioInput = dspSize
    override val pitch: AudioInput = dspPitch
    override val density: AudioInput = dspDensity
    override val texture: AudioInput = dspTexture
    override val dryWet: AudioInput = dspDryWet
    override val freeze: AudioInput = dspFreeze
    override val trigger: AudioInput = dspTrigger
    override val feedback: AudioInput = dspFeedback
    override val output: AudioOutput = dspOutput
    override val outputRight: AudioOutput = dspOutputR

    init {
        processor.init(2)
    }

    override fun setMode(mode: Int) {
        processor.parameters.mode = when (mode) {
            0 -> GrainsMode.GRANULAR
            1 -> GrainsMode.STRETCH
            2 -> GrainsMode.LOOPING_DELAY
            3 -> GrainsMode.SPECTRAL
            else -> GrainsMode.GRANULAR
        }
    }

    override fun setBypass(bypass: Boolean) {
        this.bypass = bypass
    }

    override fun process(numFrames: Int) {
        val inL = dspInputL.getBuffer()
        val inR = dspInputR.getBuffer()
        val outL = dspOutput.getBuffer()
        val outR = dspOutputR.getBuffer()

        if (bypass) {
            for (i in 0 until numFrames) { outL[i] = 0f; outR[i] = 0f }
            return
        }

        // Copy input port buffers into processor input arrays
        inL.copyInto(leftInBuffer, 0, 0, numFrames)
        inR.copyInto(rightInBuffer, 0, 0, numFrames)

        // Read parameter values from first sample of each control port
        val p = processor.parameters
        p.position = dspPosition.getBuffer()[0]
        p.size = dspSize.getBuffer()[0]
        p.pitch = dspPitch.getBuffer()[0]
        p.density = dspDensity.getBuffer()[0]
        p.feedback = dspFeedback.getBuffer()[0]
        p.texture = dspTexture.getBuffer()[0]
        p.dryWet = dspDryWet.getBuffer()[0]

        val freezeVal = dspFreeze.getBuffer()[0]
        p.freeze = freezeVal > 0.5f

        // Rising-edge trigger detection
        val currentTrig = dspTrigger.getBuffer()[0] > 0.5f
        p.trigger = currentTrig && !lastTrigState
        lastTrigState = currentTrig

        // Run the granular processor
        processor.process(leftInBuffer, rightInBuffer, leftOutBuffer, rightOutBuffer, numFrames)

        // Copy processor output into output port buffers
        leftOutBuffer.copyInto(outL, 0, 0, numFrames)
        rightOutBuffer.copyInto(outR, 0, 0, numFrames)
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspInputL.allocate(maxFrames)
        dspInputR.allocate(maxFrames)
        dspPosition.allocate(maxFrames)
        dspSize.allocate(maxFrames)
        dspPitch.allocate(maxFrames)
        dspDensity.allocate(maxFrames)
        dspTexture.allocate(maxFrames)
        dspDryWet.allocate(maxFrames)
        dspFreeze.allocate(maxFrames)
        dspTrigger.allocate(maxFrames)
        dspFeedback.allocate(maxFrames)
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

    private val inputPorts = listOf(dspInputL, dspInputR, dspPosition, dspSize, dspPitch, dspDensity, dspTexture, dspDryWet, dspFreeze, dspTrigger, dspFeedback)
    private val outputPorts = listOf(dspOutput, dspOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
