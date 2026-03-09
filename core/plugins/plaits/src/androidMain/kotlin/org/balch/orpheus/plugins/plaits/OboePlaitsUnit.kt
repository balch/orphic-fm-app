package org.balch.orpheus.plugins.plaits

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DspAudioInput
import org.balch.orpheus.core.audio.dsp.DspAudioOutput
import org.balch.orpheus.core.audio.dsp.DspProcessable
import org.balch.orpheus.core.audio.dsp.PlaitsUnit
import org.balch.orpheus.plugins.plaits.engine.SpeechEngine
import kotlin.concurrent.Volatile
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Oboe implementation of PlaitsUnit.
 *
 * Renders audio by splitting blocks into Plaits-sized sub-blocks (~24 samples)
 * for parameter interpolation. Provides audio-rate trigger input with edge detection.
 */
class OboePlaitsUnit : PlaitsUnit, DspProcessable {
    @Volatile override var enabled = true

    companion object {
        private const val PLAITS_BLOCK_SIZE = 24
        private const val LN2 = 0.6931472f
    }

    // Oboe ports
    private val oboeOutput = DspAudioOutput("Output")
    private val oboeTriggerInput = DspAudioInput("Trigger")
    private val oboeTimbreInput = DspAudioInput("TimbreMod", smoothed = true)
    private val oboeMorphInput = DspAudioInput("MorphMod", smoothed = true)
    private val oboeFrequencyInput = DspAudioInput("Frequency")

    override val output: AudioOutput = oboeOutput
    override val triggerInput: AudioInput = oboeTriggerInput
    override val frequencyInput: AudioInput = oboeFrequencyInput
    override val timbreInput: AudioInput = oboeTimbreInput
    override val morphInput: AudioInput = oboeMorphInput

    // Swappable engine
    @Volatile
    private var engine: PlaitsEngine? = null

    // Control-rate parameters (set from plugin thread, read in audio thread)
    @Volatile private var _note = 60f
    @Volatile private var _timbre = 0.5f
    @Volatile private var _morph = 0.5f
    @Volatile private var _harmonics = 0.5f
    @Volatile private var _accent = 0.8f
    @Volatile private var _speechProsody = 0.5f
    @Volatile private var _speechSpeed = 0.0f
    @Volatile private var _envSpeed = 0.0f

    // Manual trigger flag
    @Volatile private var _manualTrigger = false

    // Edge detection state
    private var lastTriggerValue = 0f

    // Percussive envelope state
    @Volatile private var _percussiveMode = false
    private var envAmplitude = 0f

    // Reusable render buffer and parameters (avoids allocation in audio thread)
    private val renderBuffer = FloatArray(PLAITS_BLOCK_SIZE)
    private val reusableParams = EngineParameters()

    override fun setEngine(engine: Any?) {
        this.engine = engine as? PlaitsEngine
    }

    override fun getEngine(): Any? = engine

    override fun setNote(note: Float) { _note = note }
    override fun setTimbre(timbre: Float) { _timbre = timbre }
    override fun setMorph(morph: Float) { _morph = morph }
    override fun setHarmonics(harmonics: Float) { _harmonics = harmonics }
    override fun setAccent(accent: Float) { _accent = accent }

    override fun trigger(accent: Float) {
        _accent = accent
        _manualTrigger = true
    }

    override fun setSpeechProsody(value: Float) { _speechProsody = value }
    override fun setSpeechSpeed(value: Float) { _speechSpeed = value }
    override fun setEnvelopeSpeed(value: Float) { _envSpeed = value }

    override fun setPercussiveMode(enabled: Boolean) {
        _percussiveMode = enabled
        if (!enabled) envAmplitude = 0f
    }

    override fun process(numFrames: Int) {
        val outputBuf = oboeOutput.getBuffer()
        val trigBuf = oboeTriggerInput.getBuffer()
        val timbreModBuf = oboeTimbreInput.getBuffer()
        val morphModBuf = oboeMorphInput.getBuffer()
        val freqBuf = oboeFrequencyInput.getBuffer()
        val eng = engine

        if (eng == null) {
            outputBuf.fill(0f, 0, numFrames)
            return
        }

        var offset = 0
        val percussive = _percussiveMode

        // Compute per-sample decay coefficient from morph (0..1 -> 30ms..2000ms)
        val decayCoeff = if (percussive) {
            val decayMs = 30f + _morph * 1970f
            val decaySamples = decayMs * org.balch.orpheus.core.audio.dsp.dspSampleRate / 1000f
            exp(-6.9f / decaySamples)
        } else 1f

        while (offset < numFrames) {
            val blockSize = minOf(PLAITS_BLOCK_SIZE, numFrames - offset)

            // Determine trigger state for this sub-block
            var triggerState = TriggerState.LOW

            // Check manual trigger (consume at first sub-block)
            if (offset == 0 && _manualTrigger) {
                _manualTrigger = false
                triggerState = TriggerState.RISING_EDGE
            }

            // Check audio-rate trigger input (edge detection)
            if (triggerState == TriggerState.LOW) {
                for (j in 0 until blockSize) {
                    val sample = trigBuf[offset + j]
                    if (sample > 0.1f && lastTriggerValue <= 0.1f) {
                        triggerState = TriggerState.RISING_EDGE
                    }
                    lastTriggerValue = sample
                }
            } else {
                // Still update lastTriggerValue
                for (j in 0 until blockSize) {
                    lastTriggerValue = trigBuf[offset + j]
                }
            }

            // Reset envelope on trigger in percussive mode
            if (percussive && triggerState == TriggerState.RISING_EDGE) {
                envAmplitude = 1f
            }

            // Sample mod inputs once per sub-block (first sample of block)
            val timbreMod = timbreModBuf[offset]
            val morphMod = morphModBuf[offset]

            // Convert audio-rate frequency (Hz) to MIDI note, falling back to control-rate _note
            val freqHz = freqBuf[offset]
            val note = if (freqHz > 1f) {
                69f + 12f * ln(freqHz / 440f) / LN2
            } else _note

            reusableParams.set(
                trigger = triggerState,
                note = note,
                timbre = (_timbre + timbreMod).coerceIn(0f, 1f),
                morph = (_morph + morphMod).coerceIn(0f, 1f),
                harmonics = _harmonics,
                accent = _accent
            )

            // Apply speech-specific parameters before rendering
            (eng as? SpeechEngine)?.let {
                it.prosodyAmount = _speechProsody
                it.speed = _speechSpeed
                // Speech engine: use per-voice envSpeed as morph for word selection
                reusableParams.morph = _envSpeed
            }

            eng.render(reusableParams, renderBuffer, null, blockSize)

            // Copy to output with gain, envelope, and soft limiting
            val gain = eng.outGain
            if (percussive) {
                for (j in 0 until blockSize) {
                    outputBuf[offset + j] = softLimit(renderBuffer[j] * gain * envAmplitude)
                    envAmplitude *= decayCoeff
                }
            } else {
                for (j in 0 until blockSize) {
                    outputBuf[offset + j] = softLimit(renderBuffer[j] * gain)
                }
            }

            offset += blockSize
        }
    }

    private fun softLimit(x: Float): Float {
        return if (x.absoluteValue < 0.5f) {
            x
        } else {
            sign(x) * (0.5f + 0.5f * tanh((x.absoluteValue - 0.5f) * 2f))
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeOutput.allocate(maxFrames)
        oboeTriggerInput.allocate(maxFrames)
        oboeTimbreInput.allocate(maxFrames)
        oboeMorphInput.allocate(maxFrames)
        oboeFrequencyInput.allocate(maxFrames)
    }

    private val inputPorts = listOf(oboeTriggerInput, oboeTimbreInput, oboeMorphInput, oboeFrequencyInput)
    private val outputPorts = listOf(oboeOutput)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
