package org.balch.orpheus.plugins.flux

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DspAudioInput
import org.balch.orpheus.core.audio.dsp.DspAudioOutput
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.FluxUnit
import org.balch.orpheus.plugins.flux.engine.FluxProcessor
import kotlin.concurrent.Volatile

/**
 * Oboe implementation of FluxUnit.
 * Delegates to FluxProcessor.process() with Float-native buffers.
 */
class OboeFluxUnit : FluxUnit, DspProcessable {
    @Volatile override var enabled = true

    private val processor = FluxProcessor(org.balch.orpheus.core.audio.dsp.dspSampleRate)

    // Input ports
    private val oboeClock = DspAudioInput("Clock")
    private val oboeSpread = DspAudioInput("Spread", smoothed = true)
    private val oboeBias = DspAudioInput("Bias", smoothed = true)
    private val oboeSteps = DspAudioInput("Steps", smoothed = true)
    private val oboeDejaVu = DspAudioInput("DejaVu", smoothed = true)
    private val oboeLength = DspAudioInput("Length", smoothed = true)
    private val oboeRate = DspAudioInput("Rate", smoothed = true)
    private val oboeJitter = DspAudioInput("Jitter", smoothed = true)
    private val oboeProbability = DspAudioInput("Probability", smoothed = true)
    private val oboePulseWidth = DspAudioInput("PulseWidth", smoothed = true)

    // Output ports
    private val oboeOutput = DspAudioOutput("Output")
    private val oboeOutputX1 = DspAudioOutput("OutputX1")
    private val oboeOutputX3 = DspAudioOutput("OutputX3")
    private val oboeOutputT1 = DspAudioOutput("OutputT1")
    private val oboeOutputT2 = DspAudioOutput("OutputT2")
    private val oboeOutputT3 = DspAudioOutput("OutputT3")

    // Interface implementation
    override val clock: AudioInput = oboeClock
    override val spread: AudioInput = oboeSpread
    override val bias: AudioInput = oboeBias
    override val steps: AudioInput = oboeSteps
    override val dejaVu: AudioInput = oboeDejaVu
    override val length: AudioInput = oboeLength
    override val rate: AudioInput = oboeRate
    override val jitter: AudioInput = oboeJitter
    override val probability: AudioInput = oboeProbability
    override val pulseWidth: AudioInput = oboePulseWidth

    override val output: AudioOutput = oboeOutput
    override val outputX1: AudioOutput = oboeOutputX1
    override val outputX3: AudioOutput = oboeOutputX3
    override val outputT1: AudioOutput = oboeOutputT1
    override val outputT2: AudioOutput = oboeOutputT2
    override val outputT3: AudioOutput = oboeOutputT3

    // Pre-allocated output buffers for processor (X2 goes to main output)
    private var outX2Buf = FloatArray(0)

    @Volatile private var bypass = true
    @Volatile private var mix = 0.0f

    init {
        oboeSpread.set(0.5)
        oboeBias.set(0.5)
        oboeSteps.set(0.5)
        oboeDejaVu.set(0.0)
        oboeLength.set(8.0)
        oboeRate.set(0.5)
        oboeJitter.set(0.0)
        oboeProbability.set(0.5)
        oboePulseWidth.set(0.5)
    }

    override fun setBypass(bypass: Boolean) { this.bypass = bypass }
    override fun setMix(mix: Float) {
        this.mix = mix
        bypass = mix <= 0.001f
        processor.setMix(mix)
    }

    override fun setScale(index: Int) { processor.setScale(index) }
    override fun setTModel(index: Int) { processor.setTModel(index) }
    override fun setTRange(index: Int) { processor.setTRange(index) }
    override fun setPulseWidth(value: Float) { processor.setPulseWidth(value) }
    override fun setPulseWidthStd(value: Float) { processor.setPulseWidthStd(value) }
    override fun setControlMode(index: Int) { processor.setControlMode(index) }
    override fun setVoltageRange(index: Int) { processor.setVoltageRange(index) }
    override fun setDejaVuMode(mode: Int) { processor.setDejaVuMode(mode) }

    override fun process(numFrames: Int) {
        if (bypass) {
            oboeOutput.getBuffer().fill(0f, 0, numFrames)
            oboeOutputX1.getBuffer().fill(0f, 0, numFrames)
            oboeOutputX3.getBuffer().fill(0f, 0, numFrames)
            oboeOutputT1.getBuffer().fill(0f, 0, numFrames)
            oboeOutputT2.getBuffer().fill(0f, 0, numFrames)
            oboeOutputT3.getBuffer().fill(0f, 0, numFrames)
            return
        }

        // Read control parameters at start of block
        processor.setSpread(oboeSpread.getValue(0))
        processor.setBias(oboeBias.getValue(0))
        processor.setSteps(oboeSteps.getValue(0))
        processor.setDejaVu(oboeDejaVu.getValue(0))
        processor.setLength(oboeLength.getValue(0).toInt())
        processor.setRate(oboeRate.getValue(0))
        processor.setJitter(oboeJitter.getValue(0))
        processor.setGateProbability(oboeProbability.getValue(0))
        processor.setPulseWidth(oboePulseWidth.getValue(0))

        // Process directly with Float buffers — zero conversion overhead
        processor.process(
            clockIn = oboeClock.getBuffer(),
            outputX1 = oboeOutputX1.getBuffer(),
            outputX2 = outX2Buf,
            outputX3 = oboeOutputX3.getBuffer(),
            outputT1 = oboeOutputT1.getBuffer(),
            outputT2 = oboeOutputT2.getBuffer(),
            outputT3 = oboeOutputT3.getBuffer(),
            start = 0,
            size = numFrames
        )

        // Copy X2 to main output
        outX2Buf.copyInto(oboeOutput.getBuffer(), endIndex = numFrames)
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeClock.allocate(maxFrames)
        oboeSpread.allocate(maxFrames)
        oboeBias.allocate(maxFrames)
        oboeSteps.allocate(maxFrames)
        oboeDejaVu.allocate(maxFrames)
        oboeLength.allocate(maxFrames)
        oboeRate.allocate(maxFrames)
        oboeJitter.allocate(maxFrames)
        oboeProbability.allocate(maxFrames)
        oboePulseWidth.allocate(maxFrames)
        oboeOutput.allocate(maxFrames)
        oboeOutputX1.allocate(maxFrames)
        oboeOutputX3.allocate(maxFrames)
        oboeOutputT1.allocate(maxFrames)
        oboeOutputT2.allocate(maxFrames)
        oboeOutputT3.allocate(maxFrames)
        outX2Buf = FloatArray(maxFrames)
    }

    private val inputPorts = listOf(
        oboeClock, oboeSpread, oboeBias, oboeSteps, oboeDejaVu,
        oboeLength, oboeRate, oboeJitter, oboeProbability, oboePulseWidth
    )
    private val outputPorts = listOf(oboeOutput, oboeOutputX1, oboeOutputX3, oboeOutputT1, oboeOutputT2, oboeOutputT3)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
