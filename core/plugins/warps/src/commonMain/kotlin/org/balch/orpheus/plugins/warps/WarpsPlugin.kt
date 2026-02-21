package org.balch.orpheus.plugins.warps

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
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol

/**
 * Warps Meta-Modulator Plugin.
 * 
 * Port Map:
 * 0: Carrier Input (Audio)
 * 1: Modulator Input (Audio)
 * 2: Output Left (Audio)
 * 3: Output Right (Audio)
 * 
 * Controls (via DSL):
 * - algorithm, timbre, level1, level2, mix
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class WarpsPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Warps",
        author = "Balch"
    )
    
    companion object {
        const val URI = WARPS_URI
    }

    private val warps = dspFactory.createWarpsUnit()
    
    // Source routing pass-throughs
    private val carrierInput = dspFactory.createPassThrough()
    private val modulatorInput = dspFactory.createPassThrough()
    
    // Dry/Wet Mix routing
    private val dryGainLeft = dspFactory.createMultiply()
    private val dryGainRight = dspFactory.createMultiply()
    private val wetGainLeft = dspFactory.createMultiply()
    private val wetGainRight = dspFactory.createMultiply()
    private val mixSumLeft = dspFactory.createPassThrough()
    private val mixSumRight = dspFactory.createPassThrough()
    
    // Dry signal pass-throughs
    private val dryPassLeft = dspFactory.createPassThrough()
    private val dryPassRight = dspFactory.createPassThrough()
    
    // Internal state
    private var _algorithm = 0f
    private var _timbre = 0.5f
    private var _level1 = 0.5f
    private var _level2 = 0.5f
    private var _mix = 0f
    private var _carrierSource = 0 // WarpsSource.SYNTH
    private var _modulatorSource = 1 // WarpsSource.DRUMS
    
    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(WarpsSymbol.ALGORITHM) {
            floatType {
                default = 0f; min = 0f; max = 8f
                get { _algorithm }
                set { _algorithm = it; warps.algorithm.set(it.toDouble()) }
            }
        }
        
        controlPort(WarpsSymbol.TIMBRE) {
            floatType {
                get { _timbre }
                set { _timbre = it; warps.timbre.set(it.toDouble()) }
            }
        }
        
        controlPort(WarpsSymbol.LEVEL1) {
            floatType {
                get { _level1 }
                set { _level1 = it; warps.level1.set(it.toDouble()) }
            }
        }
        
        controlPort(WarpsSymbol.LEVEL2) {
            floatType {
                get { _level2 }
                set { _level2 = it; warps.level2.set(it.toDouble()) }
            }
        }
        
        controlPort(WarpsSymbol.MIX) {
            floatType {
                default = 0f
                get { _mix }
                set {
                    _mix = it
                    val wet = it.toDouble()
                    val dry = (1.0 - it).toDouble()
                    wetGainLeft.inputB.set(wet)
                    wetGainRight.inputB.set(wet)
                    dryGainLeft.inputB.set(dry)
                    dryGainRight.inputB.set(dry)
                    warps.setBypass(it <= 0.001f)
                }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "carrier"; name = "Carrier"; isInput = true }
        audioPort { index = 1; symbol = "modulator"; name = "Modulator"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts
    
    override val audioUnits: List<AudioUnit> = listOf(
        warps, carrierInput, modulatorInput,
        dryGainLeft, dryGainRight, wetGainLeft, wetGainRight,
        mixSumLeft, mixSumRight, dryPassLeft, dryPassRight
    )
    
    val carrierRouteInput: AudioInput get() = carrierInput.input
    val modulatorRouteInput: AudioInput get() = modulatorInput.input
    val dryInputLeft: AudioInput get() = dryPassLeft.input
    val dryInputRight: AudioInput get() = dryPassRight.input
    
    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to carrierInput.input,
        "inputRight" to modulatorInput.input,
        "algorithm" to warps.algorithm,
        "timbre" to warps.timbre,
        "level1" to warps.level1,
        "level2" to warps.level2
    )
    
    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to mixSumLeft.output,
        "outputRight" to mixSumRight.output
    )
    
    override fun initialize() {
        carrierInput.output.connect(warps.inputLeft)
        modulatorInput.output.connect(warps.inputRight)
        
        warps.output.connect(wetGainLeft.inputA)
        warps.outputRight.connect(wetGainRight.inputA)
        wetGainLeft.output.connect(mixSumLeft.input)
        wetGainRight.output.connect(mixSumRight.input)
        
        dryPassLeft.output.connect(dryGainLeft.inputA)
        dryPassRight.output.connect(dryGainRight.inputA)
        dryGainLeft.output.connect(mixSumLeft.input)
        dryGainRight.output.connect(mixSumRight.input)
        
        warps.algorithm.set(0.0)
        warps.timbre.set(0.5)
        warps.level1.set(0.5)
        warps.level2.set(0.5)
        
        setPortValue("mix", PortValue.FloatValue(0.5f))

        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Routing methods (still needed for wiring in DspSynthEngine)
    fun setCarrierSource(source: Int) { _carrierSource = source }
    fun getCarrierSource(): Int = _carrierSource
    fun setModulatorSource(source: Int) { _modulatorSource = source }
    fun getModulatorSource(): Int = _modulatorSource
    
    fun disconnectCarrier() { carrierInput.input.disconnectAll() }
    fun disconnectModulator() { modulatorInput.input.disconnectAll() }
    fun disconnectDry() {
        dryPassLeft.input.disconnectAll()
        dryPassRight.input.disconnectAll()
    }
}
