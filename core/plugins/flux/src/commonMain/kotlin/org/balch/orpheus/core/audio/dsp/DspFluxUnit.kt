package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.flux.engine.FluxProcessor

/**
 * FluxUnit implementation using FluxProcessor for real random-sequence DSP.
 * Delegates entirely to FluxProcessor.process() for block-based audio-rate processing.
 *
 * Lives in the flux plugin module (not core/audio) to avoid a circular dependency,
 * since core/plugins/flux already depends on core/audio for DspAudioInput/DspAudioOutput.
 * The WasmFluxUnitFactory in apps/composeApp can resolve this class because it depends
 * on both core/audio and core/plugins/flux.
 */
class DspFluxUnit : FluxUnit, DspProcessable {
    override var enabled = true

    private val processor = FluxProcessor(dspSampleRate)

    // Input ports (smoothed for control-rate parameters to prevent clicks)
    private val dspClock = DspAudioInput("Flux.clock")
    private val dspSpread = DspAudioInput("Flux.spread", smoothed = true)
    private val dspBias = DspAudioInput("Flux.bias", smoothed = true)
    private val dspSteps = DspAudioInput("Flux.steps", smoothed = true)
    private val dspDejaVu = DspAudioInput("Flux.dejaVu", smoothed = true)
    private val dspLength = DspAudioInput("Flux.length", smoothed = true)
    private val dspRate = DspAudioInput("Flux.rate", smoothed = true)
    private val dspJitter = DspAudioInput("Flux.jitter", smoothed = true)
    private val dspProbability = DspAudioInput("Flux.probability", smoothed = true)
    private val dspPulseWidth = DspAudioInput("Flux.pulseWidth", smoothed = true)

    // Output ports
    private val dspOutput = DspAudioOutput("Flux.x2")
    private val dspOutputX1 = DspAudioOutput("Flux.x1")
    private val dspOutputX3 = DspAudioOutput("Flux.x3")
    private val dspOutputT2 = DspAudioOutput("Flux.t2")
    private val dspOutputT1 = DspAudioOutput("Flux.t1")
    private val dspOutputT3 = DspAudioOutput("Flux.t3")

    // Interface implementation
    override val clock: AudioInput = dspClock
    override val spread: AudioInput = dspSpread
    override val bias: AudioInput = dspBias
    override val steps: AudioInput = dspSteps
    override val dejaVu: AudioInput = dspDejaVu
    override val length: AudioInput = dspLength
    override val rate: AudioInput = dspRate
    override val jitter: AudioInput = dspJitter
    override val probability: AudioInput = dspProbability
    override val pulseWidth: AudioInput = dspPulseWidth
    override val output: AudioOutput = dspOutput
    override val outputX1: AudioOutput = dspOutputX1
    override val outputX3: AudioOutput = dspOutputX3
    override val outputT2: AudioOutput = dspOutputT2
    override val outputT1: AudioOutput = dspOutputT1
    override val outputT3: AudioOutput = dspOutputT3

    // Pre-allocated buffer for X2 output (processor writes here, then copied to main output)
    private var outX2Buf = FloatArray(0)

    private var bypass = true
    private var mix = 0.0f

    init {
        dspSpread.set(0.5)
        dspBias.set(0.5)
        dspSteps.set(0.5)
        dspDejaVu.set(0.0)
        dspLength.set(8.0)
        dspRate.set(0.5)
        dspJitter.set(0.0)
        dspProbability.set(0.5)
        dspPulseWidth.set(0.5)
    }

    override fun setBypass(bypass: Boolean) { this.bypass = bypass }
    override fun setMix(mix: Float) {
        this.mix = mix
        bypass = mix <= PLUGIN_DISABLE_THRESHOLD
        processor.setMix(mix)
    }

    override fun setScale(index: Int) { processor.setScale(index) }
    override fun setTModel(index: Int) { processor.setTModel(index) }
    override fun setTRange(index: Int) { processor.setTRange(index) }
    override fun setPulseWidth(value: Float) { processor.setPulseWidth(value) }
    override fun setPulseWidthStd(value: Float) { processor.setPulseWidthStd(value) }
    override fun setControlMode(index: Int) { processor.setControlMode(index) }
    override fun setVoltageRange(index: Int) { processor.setVoltageRange(index) }

    override fun process(numFrames: Int) {
        if (bypass) {
            dspOutput.getBuffer().fill(0f, 0, numFrames)
            dspOutputX1.getBuffer().fill(0f, 0, numFrames)
            dspOutputX3.getBuffer().fill(0f, 0, numFrames)
            dspOutputT1.getBuffer().fill(0f, 0, numFrames)
            dspOutputT2.getBuffer().fill(0f, 0, numFrames)
            dspOutputT3.getBuffer().fill(0f, 0, numFrames)
            return
        }

        // Read control parameters at start of block
        processor.setSpread(dspSpread.getValue(0))
        processor.setBias(dspBias.getValue(0))
        processor.setSteps(dspSteps.getValue(0))
        processor.setDejaVu(dspDejaVu.getValue(0))
        processor.setLength(dspLength.getValue(0).toInt())
        processor.setRate(dspRate.getValue(0))
        processor.setJitter(dspJitter.getValue(0))
        processor.setGateProbability(dspProbability.getValue(0))
        processor.setPulseWidth(dspPulseWidth.getValue(0))

        // Process directly with Float-native buffers
        processor.process(
            clockIn = dspClock.getBuffer(),
            outputX1 = dspOutputX1.getBuffer(),
            outputX2 = outX2Buf,
            outputX3 = dspOutputX3.getBuffer(),
            outputT1 = dspOutputT1.getBuffer(),
            outputT2 = dspOutputT2.getBuffer(),
            outputT3 = dspOutputT3.getBuffer(),
            start = 0,
            size = numFrames
        )

        // Copy X2 to main output
        outX2Buf.copyInto(dspOutput.getBuffer(), endIndex = numFrames)
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspClock.allocate(maxFrames)
        dspSpread.allocate(maxFrames)
        dspBias.allocate(maxFrames)
        dspSteps.allocate(maxFrames)
        dspDejaVu.allocate(maxFrames)
        dspLength.allocate(maxFrames)
        dspRate.allocate(maxFrames)
        dspJitter.allocate(maxFrames)
        dspProbability.allocate(maxFrames)
        dspPulseWidth.allocate(maxFrames)
        dspOutput.allocate(maxFrames)
        dspOutputX1.allocate(maxFrames)
        dspOutputX3.allocate(maxFrames)
        dspOutputT2.allocate(maxFrames)
        dspOutputT1.allocate(maxFrames)
        dspOutputT3.allocate(maxFrames)
        outX2Buf = FloatArray(maxFrames)
    }

    private val inputPorts = listOf(
        dspClock, dspSpread, dspBias, dspSteps, dspDejaVu,
        dspLength, dspRate, dspJitter, dspProbability, dspPulseWidth
    )
    private val outputPorts = listOf(dspOutput, dspOutputX1, dspOutputX3, dspOutputT2, dspOutputT1, dspOutputT3)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
