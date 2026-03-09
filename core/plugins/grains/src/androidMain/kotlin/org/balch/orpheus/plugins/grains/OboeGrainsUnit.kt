package org.balch.orpheus.plugins.grains

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DspAudioInput
import org.balch.orpheus.core.audio.dsp.DspAudioOutput
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.GrainsUnit
import org.balch.orpheus.plugins.grains.engine.GrainsMode
import org.balch.orpheus.plugins.grains.engine.GranularProcessor
import kotlin.concurrent.Volatile

/**
 * Oboe implementation of GrainsUnit.
 * Block processing with Float buffers (no conversion needed).
 */
class OboeGrainsUnit : GrainsUnit, DspProcessable {
    @Volatile override var enabled = true

    private val processor = GranularProcessor()
    @Volatile private var bypass = false

    // Audio ports
    private val oboeInputLeft = DspAudioInput("InputLeft")
    private val oboeInputRight = DspAudioInput("InputRight")
    private val oboeOutputLeft = DspAudioOutput("OutputLeft")
    private val oboeOutputRight = DspAudioOutput("OutputRight")

    // Parameter ports
    private val oboePosition = DspAudioInput("Position", smoothed = true)
    private val oboeSize = DspAudioInput("Size", smoothed = true)
    private val oboePitch = DspAudioInput("Pitch", smoothed = true)
    private val oboeDensity = DspAudioInput("Density", smoothed = true)
    private val oboeTexture = DspAudioInput("Texture", smoothed = true)
    private val oboeDryWet = DspAudioInput("DryWet", smoothed = true)
    private val oboeFreeze = DspAudioInput("Freeze")
    private val oboeTrigger = DspAudioInput("Trigger")

    // Interface implementation
    override val output: AudioOutput = oboeOutputLeft
    override val outputRight: AudioOutput = oboeOutputRight

    override val inputLeft: AudioInput = oboeInputLeft
    override val inputRight: AudioInput = oboeInputRight

    override val position: AudioInput = oboePosition
    override val size: AudioInput = oboeSize
    override val pitch: AudioInput = oboePitch
    override val density: AudioInput = oboeDensity
    override val texture: AudioInput = oboeTexture
    override val dryWet: AudioInput = oboeDryWet
    override val freeze: AudioInput = oboeFreeze
    override val trigger: AudioInput = oboeTrigger

    private var lastTrigState = false

    init {
        processor.init(2)

        oboePosition.set(0.0)
        oboeSize.set(0.0)
        oboePitch.set(0.0)
        oboeDensity.set(0.0)
        oboeTexture.set(0.0)
        oboeDryWet.set(0.5)
        oboeFreeze.set(0.0)
        oboeTrigger.set(0.0)
    }

    override fun setMode(mode: Int) {
        processor.parameters.mode = when (mode) {
            0 -> GrainsMode.GRANULAR
            1 -> GrainsMode.LOOPING_DELAY
            2 -> GrainsMode.SHIMMER
            else -> GrainsMode.GRANULAR
        }
    }

    override fun setBypass(bypass: Boolean) { this.bypass = bypass }

    override fun process(numFrames: Int) {
        val outL = oboeOutputLeft.getBuffer()
        val outR = oboeOutputRight.getBuffer()

        if (bypass) {
            // Dry passthrough — voices flow through Grains to StereoSum
            System.arraycopy(oboeInputLeft.getBuffer(), 0, outL, 0, numFrames)
            System.arraycopy(oboeInputRight.getBuffer(), 0, outR, 0, numFrames)
            return
        }

        // Update parameters from ports
        val p = processor.parameters
        p.position = oboePosition.getValue(0)
        p.size = oboeSize.getValue(0)
        p.pitch = oboePitch.getValue(0)
        p.density = oboeDensity.getValue(0)
        p.feedback = 0f
        p.texture = oboeTexture.getValue(0)
        p.dryWet = oboeDryWet.getValue(0)

        val freezeVal = oboeFreeze.getValue(0)
        p.freeze = freezeVal > 0.5f

        val trigVal = oboeTrigger.getValue(0)
        val currentTrig = trigVal > 0.5f
        p.trigger = currentTrig && !lastTrigState
        lastTrigState = currentTrig

        // Process - GranularProcessor works with Float buffers directly
        processor.process(
            oboeInputLeft.getBuffer(),
            oboeInputRight.getBuffer(),
            outL,
            outR,
            numFrames
        )
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeInputLeft.allocate(maxFrames)
        oboeInputRight.allocate(maxFrames)
        oboeOutputLeft.allocate(maxFrames)
        oboeOutputRight.allocate(maxFrames)
        oboePosition.allocate(maxFrames)
        oboeSize.allocate(maxFrames)
        oboePitch.allocate(maxFrames)
        oboeDensity.allocate(maxFrames)
        oboeTexture.allocate(maxFrames)
        oboeDryWet.allocate(maxFrames)
        oboeFreeze.allocate(maxFrames)
        oboeTrigger.allocate(maxFrames)
    }

    private val inputPorts = listOf(
        oboeInputLeft, oboeInputRight,
        oboePosition, oboeSize, oboePitch, oboeDensity,
        oboeTexture, oboeDryWet, oboeFreeze, oboeTrigger
    )
    private val outputPorts = listOf(oboeOutputLeft, oboeOutputRight)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
