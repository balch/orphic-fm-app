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
        // Default gains - calibrated to sit just below synth pad volume
        updateGains()
    }
    
    private var mix = 0.7f
    
    fun setMix(value: Float) {
        mix = value.coerceIn(0f, 1f)
        updateGains()
    }
    
    fun getMix(): Float = mix
    
    private fun updateGains() {
        val baseGain = 1.6f
        val finalGain = baseGain * mix
        drumGainLeft.inputB.set(finalGain.toDouble())
        drumGainRight.inputB.set(finalGain.toDouble())
    }

    /**
     * Trigger a drum sound.
     * @param type 0=BD, 1=SD, 2=HH
     */
    // State duplication for persistence
    private val frequencies = FloatArray(3) { 0.5f }
    private val tones = FloatArray(3) { 0.5f }
    private val decays = FloatArray(3) { 0.5f }
    private val p4s = FloatArray(3) { 0.5f }
    private val p5s = FloatArray(3) { 0.5f }

    fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        p4: Float = 0.5f,
        p5: Float = 0.5f
    ) {
        // Cache normalized state
        if (type in 0..2) {
            frequencies[type] = frequency
            tones[type] = tone
            decays[type] = decay
            p4s[type] = p4
            p5s[type] = p5
        }
        val scaledFreq = when(type) {
            0 -> 20f + frequency * 180f
            1 -> 100f + frequency * 400f
            2 -> 300f + frequency * 700f
            else -> frequency
        }
        drumUnit.trigger(type, accent, scaledFreq, tone, decay, p4, p5)
    }

    fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        p4: Float,
        p5: Float
    ) {
        // Cache normalized state
        if (type in 0..2) {
            frequencies[type] = frequency
            tones[type] = tone
            decays[type] = decay
            p4s[type] = p4
            p5s[type] = p5
        }
        val scaledFreq = when(type) {
            0 -> 20f + frequency * 180f
            1 -> 100f + frequency * 400f
            2 -> 300f + frequency * 700f
            else -> frequency
        }
        drumUnit.setParameters(type, scaledFreq, tone, decay, p4, p5)
    }

    fun trigger(type: Int, accent: Float) {
        drumUnit.trigger(type, accent)
    }
    
    // Getters for persistence
    fun getFrequency(type: Int) = frequencies.getOrElse(type) { 0.5f }
    fun getTone(type: Int) = tones.getOrElse(type) { 0.5f }
    fun getDecay(type: Int) = decays.getOrElse(type) { 0.5f }
    fun getP4(type: Int) = p4s.getOrElse(type) { 0.5f }
    fun getP5(type: Int) = p5s.getOrElse(type) { 0.5f }
}
