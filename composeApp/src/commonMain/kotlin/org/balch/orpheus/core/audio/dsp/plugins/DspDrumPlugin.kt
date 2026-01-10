package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * DSP Plugin for specialized 808-style drum synthesis.
 * 
 * Uses the DrumUnit which wraps AnalogBassDrum, AnalogSnareDrum, and MetallicHiHat.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspDrumPlugin(
    audioEngine: AudioEngine
) : DspPlugin {

    private val drumUnit = audioEngine.createDrumUnit()
    
    // Stereo output gain for drums
    private val drumGainLeft = audioEngine.createMultiply()
    private val drumGainRight = audioEngine.createMultiply()

    override val audioUnits: List<AudioUnit> = listOf(
        drumUnit, drumGainLeft, drumGainRight
    )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "outputLeft" to drumGainLeft.output,
            "outputRight" to drumGainRight.output
        )

    override fun initialize() {
        // Connect drum unit to output gains
        drumUnit.output.connect(drumGainLeft.inputA)
        drumUnit.output.connect(drumGainRight.inputA)
        
        // Default gains - calibrated to sit just below synth pad volume
        drumGainLeft.inputB.set(1.6)
        drumGainRight.inputB.set(1.6)
    }

    /**
     * Trigger a drum sound.
     * @param type 0=BD, 1=SD, 2=HH
     */
    fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        p4: Float = 0.5f,
        p5: Float = 0.5f
    ) {
        drumUnit.trigger(type, accent, frequency, tone, decay, p4, p5)
    }

    fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        p4: Float,
        p5: Float
    ) {
        drumUnit.setParameters(type, frequency, tone, decay, p4, p5)
    }

    fun trigger(type: Int, accent: Float) {
        drumUnit.trigger(type, accent)
    }
}
