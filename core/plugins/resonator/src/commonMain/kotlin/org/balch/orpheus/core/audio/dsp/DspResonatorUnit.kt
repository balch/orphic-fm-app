package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.resonator.engine.ModalResonator
import org.balch.orpheus.plugins.resonator.engine.ResonatorString

/**
 * DSP ResonatorUnit — delegates to the existing [ModalResonator] and
 * [ResonatorString] engines for real physical-modeling resonance.
 *
 * Modes:
 * - 0 = Modal: bank of SVF bandpass filters simulating resonant modes
 * - 1 = String: Karplus-Strong comb-filter delay line
 * - 2 = Sympathetic: modal feeds into string for coupled resonance
 *
 * On [strum], the normalized frequency is set on both engines and an impulse
 * excitation is injected at the first sample of the next process block.
 */
class DspResonatorUnit : ResonatorUnit, DspProcessable {
    override var enabled = true

    // Engines (same reduced mode count as JsynResonatorUnit for CPU budget)
    private val modalResonator = ModalResonator(maxModes = 24)
    private val stringResonator = ResonatorString()

    // State
    private var resonatorEnabled = false
    private var mode = 0 // 0=Modal, 1=String, 2=Sympathetic
    private var strumPending = false
    private var strumFrequency = 220f

    // Audio ports
    private val dspInput = DspAudioInput("Resonator.in")
    private val dspOutput = DspAudioOutput("Resonator.out")
    private val dspAuxOutput = DspAudioOutput("Resonator.aux")

    override val input: AudioInput = dspInput
    override val output: AudioOutput = dspOutput
    override val auxOutput: AudioOutput = dspAuxOutput

    init {
        modalResonator.init()
        stringResonator.init()
    }

    // ── ResonatorUnit interface ─────────────────────────────────────────

    override fun setResonatorEnabled(enabled: Boolean) {
        resonatorEnabled = enabled
    }

    override fun setMode(mode: Int) {
        this.mode = mode.coerceIn(0, 2)
    }

    override fun setStructure(value: Float) {
        modalResonator.structure = value.coerceIn(0f, 1f)
    }

    override fun setBrightness(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        modalResonator.brightness = clamped
        stringResonator.brightness = clamped
    }

    override fun setDamping(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        modalResonator.damping = clamped
        stringResonator.damping = clamped
    }

    override fun setPosition(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        modalResonator.position = clamped
        stringResonator.position = clamped
    }

    override fun strum(frequency: Float) {
        strumFrequency = frequency.coerceIn(20f, 20000f)
        strumPending = true
    }

    // ── DspProcessable ──────────────────────────────────────────────────

    override fun process(numFrames: Int) {
        val inBuf = dspInput.getBuffer()
        val outBuf = dspOutput.getBuffer()
        val auxBuf = dspAuxOutput.getBuffer()

        for (i in 0 until numFrames) {
            val inputSample = inBuf[i]

            // Handle strum trigger on the first sample of the block
            val excitation = if (i == 0 && strumPending) {
                strumPending = false
                val normalizedFreq = strumFrequency / dspSampleRate
                modalResonator.frequency = normalizedFreq
                stringResonator.frequency = normalizedFreq
                1.0f // impulse excitation
            } else {
                inputSample
            }

            if (!resonatorEnabled) {
                outBuf[i] = inputSample
                auxBuf[i] = 0f
                continue
            }

            when (mode) {
                0 -> { // Modal
                    modalResonator.process(excitation)
                    outBuf[i] = modalResonator.outOdd
                    auxBuf[i] = modalResonator.outEven
                }
                1 -> { // String
                    stringResonator.process(excitation)
                    outBuf[i] = stringResonator.outMain
                    auxBuf[i] = stringResonator.outAux
                }
                2 -> { // Sympathetic (modal -> string)
                    modalResonator.process(excitation)
                    stringResonator.process(modalResonator.outOdd)
                    outBuf[i] = stringResonator.outMain
                    auxBuf[i] = modalResonator.outEven
                }
                else -> {
                    outBuf[i] = inputSample
                    auxBuf[i] = 0f
                }
            }
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspInput.allocate(maxFrames)
        dspOutput.allocate(maxFrames)
        dspAuxOutput.allocate(maxFrames)
    }

    private val inputPorts = listOf(dspInput)
    private val outputPorts = listOf(dspOutput, dspAuxOutput)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
