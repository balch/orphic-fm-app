package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.GrainsUnit

/**
 * Grains Texture Synthesizer Plugin.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspGrainsPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    // The core unit
    private val grains: GrainsUnit = audioEngine.createGrainsUnit()

    override val audioUnits: List<AudioUnit> = listOf(grains)

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "inputLeft" to grains.inputLeft,
            "inputRight" to grains.inputRight,
            // Parameters exposed for modulation
            "position" to grains.position,
            "size" to grains.size,
            "pitch" to grains.pitch,
            "density" to grains.density,
            "texture" to grains.texture,
            "dryWet" to grains.dryWet,
            "freeze" to grains.freeze,
            "trigger" to grains.trigger
        )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "output" to grains.output,           // Left channel (main output from AudioUnit)
            "outputRight" to grains.outputRight  // Right channel (defined in GrainsUnit interface)
        )

    override fun initialize() {
        // Set defaults
        grains.setMode(0) // Granular (default)
        grains.dryWet.set(0.5)
        grains.density.set(0.5) // Feedback / Grain overlap
        grains.position.set(0.2) // Delay time
    }
    
    /**
     * Set the processing mode.
     * @param mode 0=Granular, 1=Reverse, 2=Shimmer, 3=Spectral, 4=Karplus-Strong
     */
    fun setMode(mode: Int) {
        grains.setMode(mode)
    }
}
