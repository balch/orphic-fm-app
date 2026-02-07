package org.balch.orpheus.plugins.beats

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BEATS_URI
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol

/**
 * Beats Plugin — stores beat generation parameters as proper ports.
 *
 * This plugin has no audio units; it exists purely to expose
 * beats state through the port/preset system. The actual beat
 * generation is handled by DrumBeatsGenerator in the app layer.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class BeatsPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Beats",
        author = "Orpheus"
    )

    companion object {
        const val URI = BEATS_URI
    }

    private var _x = 0.5f
    private var _y = 0.5f
    private var _bpm = 120f
    private var _mix = 0.7f
    private var _randomness = 0f
    private var _swing = 0f
    private var _mode = 0
    private val _densities = FloatArray(3) { 0.5f }
    private val _euclideanLengths = IntArray(3) { 16 }

    private val portDefs = ports(startIndex = 0) {
        controlPort(BeatsSymbol.X) {
            floatType { default = 0.5f; get { _x }; set { _x = it } }
        }
        controlPort(BeatsSymbol.Y) {
            floatType { default = 0.5f; get { _y }; set { _y = it } }
        }
        controlPort(BeatsSymbol.BPM) {
            floatType { default = 120f; min = 20f; max = 300f; get { _bpm }; set { _bpm = it } }
        }
        controlPort(BeatsSymbol.MIX) {
            floatType { default = 0.7f; get { _mix }; set { _mix = it } }
        }
        controlPort(BeatsSymbol.RANDOMNESS) {
            floatType { get { _randomness }; set { _randomness = it } }
        }
        controlPort(BeatsSymbol.SWING) {
            floatType { get { _swing }; set { _swing = it } }
        }
        controlPort(BeatsSymbol.MODE) {
            intType { get { _mode }; set { _mode = it } }
        }
        for (i in 0 until 3) {
            controlPort(BeatsSymbol.density(i)) {
                floatType { default = 0.5f; get { _densities[i] }; set { _densities[i] = it } }
            }
            controlPort(BeatsSymbol.euclidean(i)) {
                intType { default = 16; get { _euclideanLengths[i] }; set { _euclideanLengths[i] = it } }
            }
        }
    }

    override val ports: List<Port> = portDefs.ports
    override val audioUnits: List<AudioUnit> = emptyList()
    override val inputs: Map<String, AudioInput> = emptyMap()
    override val outputs: Map<String, AudioOutput> = emptyMap()

    override fun initialize() {}
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
