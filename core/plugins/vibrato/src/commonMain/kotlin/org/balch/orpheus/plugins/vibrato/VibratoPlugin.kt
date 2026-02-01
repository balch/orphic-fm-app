package org.balch.orpheus.plugins.vibrato

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
import org.balch.orpheus.core.audio.dsp.lv2.ControlPort
import org.balch.orpheus.core.audio.dsp.lv2.PluginInfo
import org.balch.orpheus.core.audio.dsp.lv2.Port
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.Lv2DspPlugin

/**
 * Vibrato Plugin (Global pitch wobble).
 * 
 * Port Map:
 * 0: Output (Control Output)
 * 1: Depth (Control Input, 0..1)
 * 2: Rate (Control Input, Hz)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class VibratoPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : Lv2DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.vibrato",
        name = "Vibrato",
        author = "Balch"
    )

    override val ports: List<Port> = listOf(
        ControlPort(0, "output", "Output", 0f, -1f, 1f),
        ControlPort(1, "depth", "Depth", 0f, 0f, 1f),
        ControlPort(2, "rate", "Rate", 5.0f, 0.1f, 20.0f)
    )

    private val lfo = dspFactory.createSineOscillator()
    private val depthGain = dspFactory.createMultiply()

    private var _vibrato = 0.0f

    override val audioUnits: List<AudioUnit> = listOf(
        lfo, depthGain
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to depthGain.output
    )

    override val inputs: Map<String, AudioInput> = emptyMap()

    override fun initialize() {
        lfo.frequency.set(5.0)
        lfo.amplitude.set(1.0)
        lfo.output.connect(depthGain.inputA)
        depthGain.inputB.set(0.0)

        // Register with engine
        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    fun setDepth(amount: Float) {
        _vibrato = amount
        val depthHz = amount * 20.0
        depthGain.inputB.set(depthHz)
    }

    fun getDepth(): Float = _vibrato
}
