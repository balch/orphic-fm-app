package org.balch.orpheus.plugins.plaits

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.JsynAudioInput
import org.balch.orpheus.core.audio.dsp.JsynAudioOutput
import org.balch.orpheus.core.audio.dsp.PlaitsUnit
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlin.math.tanh

/**
 * JSyn UnitGenerator wrapping a swappable [PlaitsEngine].
 *
 * Renders audio by splitting JSyn blocks into Plaits-sized sub-blocks (~24 samples)
 * for parameter interpolation. Provides audio-rate trigger input with edge detection.
 */
class JsynPlaitsUnit : UnitGenerator(), PlaitsUnit {

    companion object {
        /** Sub-block size matching Plaits' internal rendering granularity. */
        private const val PLAITS_BLOCK_SIZE = 24
    }

    // JSyn ports
    private val jsynOutput = UnitOutputPort("Output")
    private val jsynTriggerInput = UnitInputPort("Trigger")

    override val output: AudioOutput = JsynAudioOutput(jsynOutput)
    override val triggerInput: AudioInput = JsynAudioInput(jsynTriggerInput)

    // Swappable engine
    @Volatile
    private var engine: PlaitsEngine? = null

    // Control-rate parameters (set from plugin thread, read in audio thread)
    @Volatile private var _note = 60f
    @Volatile private var _timbre = 0.5f
    @Volatile private var _morph = 0.5f
    @Volatile private var _harmonics = 0.5f
    @Volatile private var _accent = 0.8f

    // Manual trigger flag
    @Volatile private var _manualTrigger = false

    // Edge detection state
    private var lastTriggerValue = 0.0

    // Reusable render buffer
    private val renderBuffer = FloatArray(PLAITS_BLOCK_SIZE)

    init {
        addPort(jsynOutput)
        addPort(jsynTriggerInput)
    }

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

    override fun generate(start: Int, end: Int) {
        val outputs = jsynOutput.values
        val trigInputs = jsynTriggerInput.values
        val eng = engine

        if (eng == null) {
            for (i in start until end) {
                outputs[i] = 0.0
            }
            return
        }

        val totalSamples = end - start
        var offset = 0

        while (offset < totalSamples) {
            val blockSize = minOf(PLAITS_BLOCK_SIZE, totalSamples - offset)

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
                    val sample = trigInputs[start + offset + j]
                    if (sample > 0.1 && lastTriggerValue <= 0.1) {
                        triggerState = TriggerState.RISING_EDGE
                    }
                    lastTriggerValue = sample
                }
            } else {
                // Still update lastTriggerValue
                for (j in 0 until blockSize) {
                    lastTriggerValue = trigInputs[start + offset + j]
                }
            }

            val params = EngineParameters(
                trigger = triggerState,
                note = _note,
                timbre = _timbre,
                morph = _morph,
                harmonics = _harmonics,
                accent = _accent
            )

            eng.render(params, renderBuffer, null, blockSize)

            // Copy to output with gain and soft limiting
            val gain = eng.outGain
            for (j in 0 until blockSize) {
                outputs[start + offset + j] = softLimit(renderBuffer[j] * gain).toDouble()
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
}
