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
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.GRAINS_URI
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol

/**
 * Grains Texture Synthesizer Plugin.
 * 
 * Port Map:
 * 0: Left Input (Audio)
 * 1: Right Input (Audio)
 * 2: Output Left (Audio)
 * 3: Output Right (Audio)
 * 
 * Controls (via DSL):
 * - position, size, pitch, density, texture, dry_wet, freeze, mode
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class GrainsPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Grains",
        author = "Balch"
    )
    
    companion object {
        const val URI = GRAINS_URI
    }

    private val grains = dspFactory.createGrainsUnit()

    // Internal state
    private var _position = 0.2f
    private var _size = 0.5f
    private var _pitch = 0.0f
    private var _density = 0.5f
    private var _texture = 0.5f
    private var _dryWet = 0f
    private var _freeze = false
    private var _trigger = false
    private var _mode = 0

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(GrainsSymbol.POSITION) {
            floatType {
                default = 0.2f
                get { _position }
                set { _position = it; grains.position.set(it.toDouble()) }
            }
        }
        
        controlPort(GrainsSymbol.SIZE) {
            floatType {
                get { _size }
                set { _size = it; grains.size.set(it.toDouble()) }
            }
        }
        
        controlPort(GrainsSymbol.PITCH) {
            floatType {
                default = 0.0f; min = -1f; max = 1f
                get { _pitch }
                set { _pitch = it; grains.pitch.set(it.toDouble()) }
            }
        }
        
        controlPort(GrainsSymbol.DENSITY) {
            floatType {
                get { _density }
                set { _density = it; grains.density.set(it.toDouble()) }
            }
        }
        
        controlPort(GrainsSymbol.TEXTURE) {
            floatType {
                get { _texture }
                set { _texture = it; grains.texture.set(it.toDouble()) }
            }
        }
        
        controlPort(GrainsSymbol.DRY_WET) {
            floatType {
                default = 0f
                get { _dryWet }
                set { _dryWet = it; grains.dryWet.set(it.toDouble()); grains.setBypass(it <= 0.001f) }
            }
        }
        
        controlPort(GrainsSymbol.FREEZE) {
            boolType {
                get { _freeze }
                set { _freeze = it; grains.freeze.set(if (it) 1.0 else 0.0) }
            }
        }
        
        controlPort(GrainsSymbol.TRIGGER) {
            boolType {
                get { _trigger }
                set { _trigger = it; grains.trigger.set(if (it) 1.0 else 0.0) }
            }
        }

        controlPort(GrainsSymbol.MODE) {
            intType {
                min = 0; max = 2
                options = listOf("Granular", "Reverse", "Shimmer")
                get { _mode }
                set { _mode = it; grains.setMode(it) }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

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

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
