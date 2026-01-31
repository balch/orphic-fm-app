package org.balch.orpheus.plugins.grains

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.lv2.AudioPort
import org.balch.orpheus.core.audio.dsp.lv2.ControlPort
import org.balch.orpheus.core.audio.dsp.lv2.PluginInfo
import org.balch.orpheus.core.audio.dsp.lv2.Port
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.Lv2DspPlugin

/**
 * Grains Texture Synthesizer Plugin.
 * 
 * Port Map:
 * 0: Left Input (Audio)
 * 1: Right Input (Audio)
 * 2: Output Left (Audio)
 * 3: Output Right (Audio)
 * 4: Position (Control Input, 0..1)
 * 5: Size (Control Input, 0..1)
 * 6: Pitch (Control Input, -1..1)
 * 7: Density (Control Input, 0..1)
 * 8: Texture (Control Input, 0..1)
 * 9: Dry/Wet (Control Input, 0..1)
 * 10: Freeze (Control Input, bool)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class GrainsPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : Lv2DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.grains",
        name = "Grains",
        author = "Balch"
    )

    override val ports: List<Port> = listOf(
        AudioPort(0, "in_l", "Input Left", true),
        AudioPort(1, "in_r", "Input Right", true),
        AudioPort(2, "out_l", "Output Left", false),
        AudioPort(3, "out_r", "Output Right", false),
        ControlPort(4, "position", "Position", 0.2f, 0f, 1f),
        ControlPort(5, "size", "Size", 0.5f, 0f, 1f),
        ControlPort(6, "pitch", "Pitch", 0.0f, -1f, 1f),
        ControlPort(7, "density", "Density", 0.5f, 0f, 1f),
        ControlPort(8, "texture", "Texture", 0.5f, 0f, 1f),
        ControlPort(9, "dry_wet", "Dry/Wet", 0.5f, 0f, 1f),
        ControlPort(10, "freeze", "Freeze", 0.0f, 0f, 1f)
    )

    private val grains = dspFactory.createGrainsUnit()

    override val audioUnits: List<AudioUnit> = listOf(grains)

    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to grains.inputLeft,
        "inputRight" to grains.inputRight,
        "position" to grains.position,
        "size" to grains.size,
        "pitch" to grains.pitch,
        "density" to grains.density,
        "texture" to grains.texture,
        "dryWet" to grains.dryWet,
        "freeze" to grains.freeze,
        "trigger" to grains.trigger
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to grains.output,
        "outputRight" to grains.outputRight
    )

    override fun initialize() {
        grains.setMode(0) 
        grains.dryWet.set(0.5)
        grains.density.set(0.5)
        grains.position.set(0.2)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    fun setMode(mode: Int) { grains.setMode(mode) }
}
