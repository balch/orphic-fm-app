package org.balch.orpheus.plugins.resonator

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DspAudioInput
import org.balch.orpheus.core.audio.dsp.DspAudioOutput
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.ResonatorUnit
import org.balch.orpheus.plugins.resonator.engine.ModalResonator
import org.balch.orpheus.plugins.resonator.engine.ResonatorString
import kotlin.concurrent.Volatile

class OboeResonatorUnit : ResonatorUnit, DspProcessable {
    @Volatile override var enabled = true

    private val modalResonator = ModalResonator(maxModes = 24)
    private val stringResonator = ResonatorString()

    // Ports
    private val oboeInput = DspAudioInput("Input")
    private val oboeOutput = DspAudioOutput("Output")
    private val oboeAuxOutput = DspAudioOutput("AuxOutput")

    override val input: AudioInput = oboeInput
    override val output: AudioOutput = oboeOutput
    override val auxOutput: AudioOutput = oboeAuxOutput

    // State
    private var resonatorEnabled = false
    private var mode = 0 // 0=Modal, 1=String, 2=Sympathetic
    private var structure = 0.25f
    private var brightness = 0.5f
    private var damping = 0.3f
    private var position = 0.5f

    // Strum trigger
    private var strumPending = false
    private var strumFrequency = 220f

    init {
        modalResonator.init()
        stringResonator.init()
    }

    override fun setResonatorEnabled(enabled: Boolean) { this.resonatorEnabled = enabled }

    override fun setMode(mode: Int) { this.mode = mode.coerceIn(0, 2) }

    override fun setStructure(value: Float) {
        structure = value.coerceIn(0f, 1f)
        modalResonator.structure = structure
    }

    override fun setBrightness(value: Float) {
        brightness = value.coerceIn(0f, 1f)
        modalResonator.brightness = brightness
        stringResonator.brightness = brightness
    }

    override fun setDamping(value: Float) {
        damping = value.coerceIn(0f, 1f)
        modalResonator.damping = damping
        stringResonator.damping = damping
    }

    override fun setPosition(value: Float) {
        position = value.coerceIn(0f, 1f)
        modalResonator.position = position
        stringResonator.position = position
    }

    override fun strum(frequency: Float) {
        strumFrequency = frequency.coerceIn(20f, 20000f)
        strumPending = true
    }

    override fun process(numFrames: Int) {
        val inputBuf = oboeInput.getBuffer()
        val outputBuf = oboeOutput.getBuffer()
        val auxBuf = oboeAuxOutput.getBuffer()

        for (i in 0 until numFrames) {
            val inputSample = inputBuf[i]

            // Handle strum trigger
            val excitation = if (i == 0 && strumPending) {
                strumPending = false
                val normalizedFreq = strumFrequency / org.balch.orpheus.core.audio.dsp.dspSampleRate
                modalResonator.frequency = normalizedFreq
                stringResonator.frequency = normalizedFreq
                1.0f
            } else {
                inputSample
            }

            if (!resonatorEnabled) {
                outputBuf[i] = inputSample
                auxBuf[i] = 0f
                continue
            }

            when (mode) {
                0 -> { // Modal
                    modalResonator.process(excitation)
                    outputBuf[i] = modalResonator.outOdd
                    auxBuf[i] = modalResonator.outEven
                }
                1 -> { // String
                    stringResonator.process(excitation)
                    outputBuf[i] = stringResonator.outMain
                    auxBuf[i] = stringResonator.outAux
                }
                2 -> { // Sympathetic (uses both)
                    modalResonator.process(excitation)
                    stringResonator.process(modalResonator.outOdd)
                    outputBuf[i] = stringResonator.outMain
                    auxBuf[i] = modalResonator.outEven
                }
                else -> {
                    outputBuf[i] = inputSample
                    auxBuf[i] = 0f
                }
            }
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeInput.allocate(maxFrames)
        oboeOutput.allocate(maxFrames)
        oboeAuxOutput.allocate(maxFrames)
    }

    private val inputPorts = listOf(oboeInput)
    private val outputPorts = listOf(oboeOutput, oboeAuxOutput)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
