package org.balch.orpheus.plugins.flux

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.FluxUnit
import org.balch.orpheus.core.audio.dsp.JsynAudioInput
import org.balch.orpheus.core.audio.dsp.JsynAudioOutput
import org.balch.orpheus.plugins.flux.engine.FluxProcessor

/**
 * Android Implementation of FluxUnit using JSyn.
 * Delegates entirely to FluxProcessor.process() for block-based audio-rate processing.
 */
class JsynFluxUnit : UnitGenerator(), FluxUnit {

    private val processor = FluxProcessor(44100f)

    // JSyn Ports
    private val jsynClock = UnitInputPort("Clock")
    private val jsynSpread = UnitInputPort("Spread")
    private val jsynBias = UnitInputPort("Bias")
    private val jsynSteps = UnitInputPort("Steps")
    private val jsynDejaVu = UnitInputPort("DejaVu")
    private val jsynLength = UnitInputPort("Length")
    private val jsynRate = UnitInputPort("Rate")
    private val jsynJitter = UnitInputPort("Jitter")
    private val jsynProbability = UnitInputPort("Probability")
    private val jsynPulseWidth = UnitInputPort("PulseWidth")

    // Output
    private val jsynOutput = UnitOutputPort("Output")
    private val jsynOutputX1 = UnitOutputPort("OutputX1")
    private val jsynOutputX3 = UnitOutputPort("OutputX3")

    // Gate Outputs
    private val jsynOutputT1 = UnitOutputPort("OutputT1")
    private val jsynOutputT2 = UnitOutputPort("OutputT2")
    private val jsynOutputT3 = UnitOutputPort("OutputT3")

    // AudioUnit Wrapper Ports
    override val clock: AudioInput = JsynAudioInput(jsynClock)
    override val spread: AudioInput = JsynAudioInput(jsynSpread)
    override val bias: AudioInput = JsynAudioInput(jsynBias)
    override val steps: AudioInput = JsynAudioInput(jsynSteps)
    override val dejaVu: AudioInput = JsynAudioInput(jsynDejaVu)
    override val length: AudioInput = JsynAudioInput(jsynLength)
    override val rate: AudioInput = JsynAudioInput(jsynRate)
    override val jitter: AudioInput = JsynAudioInput(jsynJitter)
    override val probability: AudioInput = JsynAudioInput(jsynProbability)
    override val pulseWidth: AudioInput = JsynAudioInput(jsynPulseWidth)

    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    override val outputX1: AudioOutput = JsynAudioOutput(jsynOutputX1)
    override val outputX3: AudioOutput = JsynAudioOutput(jsynOutputX3)
    override val outputT1: AudioOutput = JsynAudioOutput(jsynOutputT1)
    override val outputT2: AudioOutput = JsynAudioOutput(jsynOutputT2)
    override val outputT3: AudioOutput = JsynAudioOutput(jsynOutputT3)

    init {
        addPort(jsynClock)
        addPort(jsynSpread)
        addPort(jsynBias)
        addPort(jsynSteps)
        addPort(jsynDejaVu)
        addPort(jsynLength)
        addPort(jsynRate)
        addPort(jsynJitter)
        addPort(jsynProbability)
        addPort(jsynPulseWidth)
        addPort(jsynOutput)
        addPort(jsynOutputX1)
        addPort(jsynOutputX3)
        addPort(jsynOutputT1)
        addPort(jsynOutputT2)
        addPort(jsynOutputT3)

        // Default values
        jsynSpread.set(0.5)
        jsynBias.set(0.5)
        jsynSteps.set(0.5)
        jsynDejaVu.set(0.0)
        jsynLength.set(8.0)
        jsynRate.set(0.5)
        jsynJitter.set(0.0)
        jsynProbability.set(0.5)
        jsynPulseWidth.set(0.5)
    }

    @Volatile private var bypass = true
    @Volatile private var mix = 0.0f
    override fun setBypass(bypass: Boolean) { this.bypass = bypass }
    override fun setMix(mix: Float) { this.mix = mix; bypass = mix <= 0.001f; processor.setMix(mix) }

    override fun setScale(index: Int) { processor.setScale(index) }
    override fun setTModel(index: Int) { processor.setTModel(index) }
    override fun setTRange(index: Int) { processor.setTRange(index) }
    override fun setPulseWidth(value: Float) { processor.setPulseWidth(value) }
    override fun setPulseWidthStd(value: Float) { processor.setPulseWidthStd(value) }
    override fun setControlMode(index: Int) { processor.setControlMode(index) }
    override fun setVoltageRange(index: Int) { processor.setVoltageRange(index) }

    override fun generate(start: Int, end: Int) {
        val count = end - start
        if (count <= 0) return

        if (bypass) {
            for (i in start until end) {
                jsynOutput.values[i] = 0.0
                jsynOutputX1.values[i] = 0.0
                jsynOutputX3.values[i] = 0.0
                jsynOutputT1.values[i] = 0.0
                jsynOutputT2.values[i] = 0.0
                jsynOutputT3.values[i] = 0.0
            }
            return
        }

        // Read control parameters (sample at start of block)
        processor.setSpread(jsynSpread.values[start].toFloat())
        processor.setBias(jsynBias.values[start].toFloat())
        processor.setSteps(jsynSteps.values[start].toFloat())
        processor.setDejaVu(jsynDejaVu.values[start].toFloat())
        processor.setLength(jsynLength.values[start].toInt())
        processor.setRate(jsynRate.values[start].toFloat())
        processor.setJitter(jsynJitter.values[start].toFloat())
        processor.setGateProbability(jsynProbability.values[start].toFloat())
        processor.setPulseWidth(jsynPulseWidth.values[start].toFloat())

        // Delegate block processing to FluxProcessor
        processor.process(
            clockIn = jsynClock.values,
            outputX1 = jsynOutputX1.values,
            outputX2 = jsynOutput.values,
            outputX3 = jsynOutputX3.values,
            outputT1 = jsynOutputT1.values,
            outputT2 = jsynOutputT2.values,
            outputT3 = jsynOutputT3.values,
            start = start,
            size = count
        )

    }
}
