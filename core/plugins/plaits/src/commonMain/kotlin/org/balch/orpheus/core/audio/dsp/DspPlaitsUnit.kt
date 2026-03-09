package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.TriggerState
import org.balch.orpheus.plugins.plaits.engine.SpeechEngine
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.tanh

/**
 * DSP PlaitsUnit — delegates to a swappable [PlaitsEngine].
 *
 * Renders audio by splitting blocks into Plaits-sized sub-blocks (~24 samples)
 * for parameter interpolation. Provides audio-rate trigger input with edge
 * detection and optional built-in percussive decay envelope.
 */
class DspPlaitsUnit : PlaitsUnit, DspProcessable {
    override var enabled = true

    companion object {
        /** Sub-block size matching Plaits' internal rendering granularity. */
        private const val PLAITS_BLOCK_SIZE = 24
        private const val LN2 = 0.6931472f
    }

    // --- Audio ports ---
    private val dspTrigger = DspAudioInput("Plaits.trigger")
    private val dspFrequency = DspAudioInput("Plaits.frequency")
    private val dspTimbre = DspAudioInput("Plaits.timbre", smoothed = true)
    private val dspMorph = DspAudioInput("Plaits.morph", smoothed = true)
    private val dspOutput = DspAudioOutput("Plaits.out")

    override val triggerInput: AudioInput = dspTrigger
    override val frequencyInput: AudioInput = dspFrequency
    override val timbreInput: AudioInput = dspTimbre
    override val morphInput: AudioInput = dspMorph
    override val output: AudioOutput = dspOutput

    // --- Swappable engine ---
    private var engine: PlaitsEngine? = null

    // --- Control-rate parameters ---
    private var _note = 60f
    private var _timbre = 0.5f
    private var _morph = 0.5f
    private var _harmonics = 0.5f
    private var _accent = 0.8f
    private var _speechProsody = 0.5f
    private var _speechSpeed = 0.0f
    private var _envSpeed = 0.0f

    // --- Manual trigger flag ---
    private var _manualTrigger = false

    // --- Edge detection state ---
    private var lastTriggerValue = 0f

    // --- Percussive envelope state ---
    private var _percussiveMode = false
    private var envAmplitude = 0f

    // --- Reusable render buffer and parameters (avoids allocation in audio thread) ---
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
        val outputBuf = dspOutput.getBuffer()
        val trigBuf = dspTrigger.getBuffer()
        val timbreModBuf = dspTimbre.getBuffer()
        val morphModBuf = dspMorph.getBuffer()
        val freqBuf = dspFrequency.getBuffer()
        val eng = engine

        if (eng == null) {
            outputBuf.fill(0f, 0, numFrames)
            return
        }

        var offset = 0
        val percussive = _percussiveMode
        val sampleRate = dspSampleRate

        // Compute per-sample decay coefficient from morph (0..1 -> 30ms..2000ms)
        val decayCoeff = if (percussive) {
            val decayMs = 30f + _morph * 1970f
            val decaySamples = decayMs * sampleRate / 1000f
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

    /**
     * Soft saturation curve to prevent hard clipping.
     * Linear below 0.5, tanh saturation above.
     */
    private fun softLimit(x: Float): Float {
        return if (x.absoluteValue < 0.5f) {
            x
        } else {
            sign(x) * (0.5f + 0.5f * tanh((x.absoluteValue - 0.5f) * 2f))
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspTrigger.allocate(maxFrames)
        dspFrequency.allocate(maxFrames)
        dspTimbre.allocate(maxFrames)
        dspMorph.allocate(maxFrames)
        dspOutput.allocate(maxFrames)
    }

    private val inputPorts = listOf(dspTrigger, dspTimbre, dspMorph, dspFrequency)
    private val outputPorts = listOf(dspOutput)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
