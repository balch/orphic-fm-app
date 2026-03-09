package org.balch.orpheus.plugins.warps

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DspAudioInput
import org.balch.orpheus.core.audio.dsp.DspAudioOutput
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.WarpsUnit
import org.balch.orpheus.plugins.warps.engine.WarpsProcessor
import kotlin.concurrent.Volatile

class OboeWarpsUnit : WarpsUnit, DspProcessable {
    @Volatile override var enabled = true

    private val processor = WarpsProcessor()
    @Volatile private var bypass = false

    // Audio Ports
    private val oboeInputLeft = DspAudioInput("InputLeft")
    private val oboeInputRight = DspAudioInput("InputRight")
    private val oboeOutputLeft = DspAudioOutput("OutputLeft")
    private val oboeOutputRight = DspAudioOutput("OutputRight")

    // Parameter Ports
    private val oboeAlgorithm = DspAudioInput("Algorithm", smoothed = true)
    private val oboeTimbre = DspAudioInput("Timbre", smoothed = true)
    private val oboeLevel1 = DspAudioInput("Level1", smoothed = true)
    private val oboeLevel2 = DspAudioInput("Level2", smoothed = true)

    // Interface Implementation
    override val output: AudioOutput = oboeOutputLeft
    override val outputRight: AudioOutput = oboeOutputRight

    override val inputLeft: AudioInput = oboeInputLeft
    override val inputRight: AudioInput = oboeInputRight

    override val algorithm: AudioInput = oboeAlgorithm
    override val timbre: AudioInput = oboeTimbre
    override val level1: AudioInput = oboeLevel1
    override val level2: AudioInput = oboeLevel2

    init {
        processor.init(org.balch.orpheus.core.audio.dsp.dspSampleRate)
        oboeAlgorithm.set(0.0)
        oboeTimbre.set(0.5)
        oboeLevel1.set(0.5)
        oboeLevel2.set(0.0)
    }

    override fun setBypass(bypass: Boolean) { this.bypass = bypass }

    override fun process(numFrames: Int) {
        val outL = oboeOutputLeft.getBuffer()
        val outR = oboeOutputRight.getBuffer()

        if (bypass) {
            outL.fill(0f, 0, numFrames)
            outR.fill(0f, 0, numFrames)
            return
        }

        val p = processor.parameters
        p.modulationAlgorithm = oboeAlgorithm.getValue(0)
        p.modulationParameter = oboeTimbre.getValue(0)
        p.channelDrive[0] = oboeLevel1.getValue(0)
        p.channelDrive[1] = oboeLevel2.getValue(0)

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
        oboeAlgorithm.allocate(maxFrames)
        oboeTimbre.allocate(maxFrames)
        oboeLevel1.allocate(maxFrames)
        oboeLevel2.allocate(maxFrames)
    }

    private val inputPorts = listOf(
        oboeInputLeft, oboeInputRight,
        oboeAlgorithm, oboeTimbre, oboeLevel1, oboeLevel2
    )
    private val outputPorts = listOf(oboeOutputLeft, oboeOutputRight)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
