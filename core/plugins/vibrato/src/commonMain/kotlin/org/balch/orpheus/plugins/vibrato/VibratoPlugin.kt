package org.balch.orpheus.plugins.vibrato

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioPort
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.PortSymbol
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports

/**
 * Exhaustive enum of all Vibrato plugin port symbols.
 */
enum class VibratoSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    DEPTH("depth", "Depth"),
    RATE("rate", "Rate")
}

/**
 * Vibrato Plugin (Global pitch wobble).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class VibratoPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.vibrato",
        name = "Vibrato",
        author = "Balch"
    )

    private val lfo = dspFactory.createSineOscillator()
    private val depthGain = dspFactory.createMultiply()

    // Internal state
    private var _depth = 0.0f
    private var _rate = 5.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 1) {
        float(VibratoSymbol.DEPTH) {
            default = 0f
            get { _depth }
            set {
                _depth = it
                val depthHz = it * 20.0
                depthGain.inputB.set(depthHz)
            }
        }
        
        float(VibratoSymbol.RATE) {
            default = 5.0f; min = 0.1f; max = 20.0f
            get { _rate }
            set {
                _rate = it
                lfo.frequency.set(it.toDouble())
            }
        }
    }

    private val audioPorts = listOf(
        AudioPort(0, "output", "Output", false)
    )

    override val ports: List<Port> = audioPorts + portDefs.ports

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

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Legacy setters for backward compatibility
    fun setDepth(amount: Float) = portDefs.setValue(VibratoSymbol.DEPTH, PortValue.FloatValue(amount))
    fun getDepth(): Float = _depth
}
