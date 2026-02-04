package org.balch.orpheus.plugins.warps

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
 * Exhaustive enum of all Warps plugin port symbols.
 */
enum class WarpsSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    ALGORITHM("algorithm", "Algorithm"),
    TIMBRE("timbre", "Timbre"),
    LEVEL1("level1", "Level 1"),
    LEVEL2("level2", "Level 2"),
    MIX("mix", "Mix")
}

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
        uri = "org.balch.orpheus.plugins.warps",
        name = "Warps",
        author = "Balch"
    )

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
    private var _mix = 0.5f
    private var _carrierSource = 0 // WarpsSource.SYNTH
    private var _modulatorSource = 1 // WarpsSource.DRUMS
    
    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        float(WarpsSymbol.ALGORITHM) {
            default = 0f; min = 0f; max = 8f
            get { _algorithm }
            set { _algorithm = it; warps.algorithm.set(it.toDouble()) }
        }
        
        float(WarpsSymbol.TIMBRE) {
            get { _timbre }
            set { _timbre = it; warps.timbre.set(it.toDouble()) }
        }
        
        float(WarpsSymbol.LEVEL1) {
            get { _level1 }
            set { _level1 = it; warps.level1.set(it.toDouble()) }
        }
        
        float(WarpsSymbol.LEVEL2) {
            get { _level2 }
            set { _level2 = it; warps.level2.set(it.toDouble()) }
        }
        
        float(WarpsSymbol.MIX) {
            get { _mix }
            set {
                _mix = it
                val wet = it.toDouble()
                val dry = (1.0 - it).toDouble()
                wetGainLeft.inputB.set(wet)
                wetGainRight.inputB.set(wet)
                dryGainLeft.inputB.set(dry)
                dryGainRight.inputB.set(dry)
            }
        }
    }

    private val audioPorts = listOf(
        AudioPort(0, "carrier", "Carrier", true),
        AudioPort(1, "modulator", "Modulator", true),
        AudioPort(2, "out_l", "Output Left", false),
        AudioPort(3, "out_r", "Output Right", false)
    )

    override val ports: List<Port> = audioPorts + portDefs.ports
    
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
        
        setMix(0.5f)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Legacy setters for backward compatibility
    fun setAlgorithm(value: Float) = portDefs.setValue(WarpsSymbol.ALGORITHM, PortValue.FloatValue(value))
    fun setTimbre(value: Float) = portDefs.setValue(WarpsSymbol.TIMBRE, PortValue.FloatValue(value))
    fun setLevel1(value: Float) = portDefs.setValue(WarpsSymbol.LEVEL1, PortValue.FloatValue(value))
    fun setLevel2(value: Float) = portDefs.setValue(WarpsSymbol.LEVEL2, PortValue.FloatValue(value))
    fun setMix(value: Float) = portDefs.setValue(WarpsSymbol.MIX, PortValue.FloatValue(value))
    
    fun getMix(): Float = _mix
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
