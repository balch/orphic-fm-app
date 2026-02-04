package org.balch.orpheus.plugins.flux

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
 * Exhaustive enum of all Flux plugin port symbols.
 * Provides compile-time safety and IDE auto-completion.
 */
enum class FluxSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    SPREAD("spread", "Spread"),
    BIAS("bias", "Bias"),
    STEPS("steps", "Steps"),
    DEJAVU("dejavu", "Déjà Vu"),
    LENGTH("length", "Length"),
    SCALE("scale", "Scale"),
    RATE("rate", "Rate"),
    JITTER("jitter", "Jitter"),
    PROBABILITY("probability", "Probability"),
    GATE_LENGTH("gatelength", "Gate Length")
}

/**
 * Flux Generative Sequencer Plugin.
 * Wraps the FluxUnit.
 * 
 * Port Map:
 * 0: Clock Input (Audio)
 * 1: Output Gate (Audio)
 * 2: Output CV (Audio)
 * 3: Output CV X1 (Audio)
 * 4: Output CV X3 (Audio)
 * 5: Output Trig T1 (Audio)
 * 6: Output Trig T2 (Audio)
 * 7: Output Trig T3 (Audio)
 * 
 * Controls (via DSL):
 * - spread, bias, steps, dejavu, length, scale, rate, jitter, probability, gatelength
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class FluxPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.flux",
        name = "Flux",
        author = "Balch"
    )

    val flux = dspFactory.createFluxUnit()

    // Internal state tracking
    private var _spread = 0.5f
    private var _bias = 0.5f
    private var _steps = 0.5f
    private var _dejaVu = 0.0f
    private var _length = 8
    private var _scale = 0
    private var _rate = 0.5f
    private var _jitter = 0.0f
    private var _probability = 0.5f
    private var _gateLength = 0.5f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 8) {
        float(FluxSymbol.SPREAD) {
            default = 0.5f
            get { _spread }
            set { _spread = it; flux.spread.set(it.toDouble()) }
        }
        
        float(FluxSymbol.BIAS) {
            get { _bias }
            set { _bias = it; flux.bias.set(it.toDouble()) }
        }
        
        float(FluxSymbol.STEPS) {
            get { _steps }
            set { _steps = it; flux.steps.set(it.toDouble()) }
        }
        
        float(FluxSymbol.DEJAVU) {
            default = 0f
            get { _dejaVu }
            set { _dejaVu = it; flux.dejaVu.set(it.toDouble()) }
        }
        
        int(FluxSymbol.LENGTH) {
            default = 8; min = 1; max = 16
            get { _length }
            set { _length = it; flux.length.set(it.toDouble()) }
        }
        
        int(FluxSymbol.SCALE) {
            min = 0; max = 5
            options = listOf("Major", "Minor", "Pentatonic", "Phrygian", "Dorian", "Chromatic")
            get { _scale }
            set { _scale = it; flux.setScale(it) }
        }
        
        float(FluxSymbol.RATE) {
            get { _rate }
            set { _rate = it; flux.rate.set(it.toDouble()) }
        }
        
        float(FluxSymbol.JITTER) {
            default = 0f
            get { _jitter }
            set { _jitter = it; flux.jitter.set(it.toDouble()) }
        }
        
        float(FluxSymbol.PROBABILITY) {
            get { _probability }
            set { _probability = it; flux.probability.set(it.toDouble()) }
        }
        
        float(FluxSymbol.GATE_LENGTH) {
            get { _gateLength }
            set { _gateLength = it; flux.gateLength.set(it.toDouble()) }
        }
    }

    private val audioPorts = listOf(
        AudioPort(0, "clock", "Clock In", true),
        AudioPort(1, "out", "Gate", false),
        AudioPort(2, "cv", "CV", false),
        AudioPort(3, "cv_x1", "CV X1", false),
        AudioPort(4, "cv_x3", "CV X3", false),
        AudioPort(5, "trig_t1", "Trig T1", false),
        AudioPort(6, "trig_t2", "Trig T2", false),
        AudioPort(7, "trig_t3", "Trig T3", false)
    )

    override val ports: List<Port> = audioPorts + portDefs.ports

    override val audioUnits: List<AudioUnit> = listOf(flux)

    override val inputs: Map<String, AudioInput> = mapOf(
        "clock" to flux.clock,
        "spread" to flux.spread,
        "bias" to flux.bias,
        "steps" to flux.steps,
        "dejaVu" to flux.dejaVu,
        "length" to flux.length,
        "rate" to flux.rate,
        "jitter" to flux.jitter,
        "probability" to flux.probability,
        "gateLength" to flux.gateLength
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to flux.output,
        "outputX1" to flux.outputX1,
        "outputX3" to flux.outputX3,
        "outputT1" to flux.outputT1,
        "outputT2" to flux.outputT2,
        "outputT3" to flux.outputT3
    )

    override fun initialize() {
        // Default settings matching DspFluxPlugin
        setSpread(0.5f)
        setBias(0.5f)
        setSteps(0.5f)
        setDejaVu(0.0f)
        setLength(8)
        setScale(0)
        setRate(0.5f)
        setJitter(0.0f)
        setProbability(0.5f)
        setGateLength(0.5f)
        
        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}
    
    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)


    fun setRate(value: Float) {
        _rate = value
        flux.rate.set(value.toDouble())
    }
    
    fun setSpread(value: Float) {
        _spread = value
        flux.spread.set(value.toDouble())
    }
    
    fun setBias(value: Float) {
        _bias = value
        flux.bias.set(value.toDouble())
    }
    
    fun setSteps(value: Float) {
        _steps = value
        flux.steps.set(value.toDouble())
    }
    
    fun setDejaVu(value: Float) {
        _dejaVu = value
        flux.dejaVu.set(value.toDouble())
    }
    
    fun setLength(value: Int) {
        _length = value
        flux.length.set(value.toDouble())
    }
    
    fun setScale(index: Int) {
        _scale = index
        flux.setScale(index)
    }
    
    fun setJitter(value: Float) {
        _jitter = value
        flux.jitter.set(value.toDouble())
    }
    
    fun setProbability(value: Float) {
        _probability = value
        flux.probability.set(value.toDouble())
    }
    
    fun setGateLength(value: Float) {
        _gateLength = value
        flux.gateLength.set(value.toDouble())
    }
    
    fun getSpread() = _spread
    fun getBias() = _bias
    fun getSteps() = _steps
    fun getDejaVu() = _dejaVu
    fun getLength() = _length
    fun getScale() = _scale
    fun getRate() = _rate
    fun getJitter() = _jitter
    fun getProbability() = _probability
}
