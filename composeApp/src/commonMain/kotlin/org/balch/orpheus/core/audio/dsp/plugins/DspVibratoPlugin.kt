package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * DSP Plugin for global vibrato (pitch wobble).
 * 
 * Simple sine LFO at 5Hz with depth control.
 * Output connects to voice pitch modulation inputs.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspVibratoPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    private val vibratoLfo = audioEngine.createSineOscillator()
    private val vibratoDepthGain = audioEngine.createMultiply()

    private var _vibrato = 0.0f

    override val audioUnits: List<AudioUnit> = listOf(
        vibratoLfo, vibratoDepthGain
    )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "output" to vibratoDepthGain.output
        )

    override fun initialize() {
        vibratoLfo.frequency.set(5.0)  // 5Hz wobble rate
        vibratoLfo.amplitude.set(1.0)
        vibratoLfo.output.connect(vibratoDepthGain.inputA)
        vibratoDepthGain.inputB.set(0.0)  // Default: no vibrato
    }

    fun setDepth(amount: Float) {
        _vibrato = amount
        val depthHz = amount * 20.0
        vibratoDepthGain.inputB.set(depthHz)
    }

    // Getter for state saving
    fun getDepth(): Float = _vibrato
}
