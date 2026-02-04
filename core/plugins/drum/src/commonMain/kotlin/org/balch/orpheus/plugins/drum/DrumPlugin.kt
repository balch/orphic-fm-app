package org.balch.orpheus.plugins.drum

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
import org.balch.orpheus.core.audio.dsp.PortSymbol
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports

/**
 * Exhaustive enum of all Drum plugin port symbols.
 */
enum class DrumSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MIX("mix", "Mix"),
    // Synthesis Parameters (prefix with drum type: bd_, sd_, hh_)
    BD_FREQ("bd_freq", "BD Frequency"),
    BD_TONE("bd_tone", "BD Tone"),
    BD_DECAY("bd_decay", "BD Decay"),
    BD_P4("bd_p4", "BD P4"),
    BD_P5("bd_p5", "BD P5"),
    
    SD_FREQ("sd_freq", "SD Frequency"),
    SD_TONE("sd_tone", "SD Tone"),
    SD_DECAY("sd_decay", "SD Decay"),
    SD_P4("sd_p4", "SD P4"),
    
    HH_FREQ("hh_freq", "HH Frequency"),
    HH_TONE("hh_tone", "HH Tone"),
    HH_DECAY("hh_decay", "HH Decay"),
    HH_P4("hh_p4", "HH P4"),
    
    // Routing
    BD_TRIGGER_SRC("bd_trigger_src", "BD Trigger Source"),
    BD_PITCH_SRC("bd_pitch_src", "BD Pitch Source"),
    SD_TRIGGER_SRC("sd_trigger_src", "SD Trigger Source"),
    SD_PITCH_SRC("sd_pitch_src", "SD Pitch Source"),
    HH_TRIGGER_SRC("hh_trigger_src", "HH Trigger Source"),
    HH_PITCH_SRC("hh_pitch_src", "HH Pitch Source"),
    
    // Bypass
    BYPASS("bypass", "Bypass")
}

/**
 * DSP Plugin for specialized 808-style drum synthesis.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DrumPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.drum",
        name = "Drum Machine",
        author = "Balch"
    )

    private val drumUnit = dspFactory.createDrumUnit()
    
    // Stereo output gain for drums
    private val drumGainLeft = dspFactory.createMultiply()
    private val drumGainRight = dspFactory.createMultiply()

    // Internal state
    private var _mix = 0.7f
    private val frequencies = FloatArray(3) { 0.5f }
    private val tones = FloatArray(3) { 0.5f }
    private val decays = FloatArray(3) { 0.5f }
    private val p4s = FloatArray(3) { 0.5f }
    private val p5s = FloatArray(3) { 0.5f }
    
    // Routing state (facade for engine)
    private val triggerSources = IntArray(3)
    private val pitchSources = IntArray(3)
    private var _bypass = true
    
    interface Listener {
        fun onRoutingChange(drumIndex: Int, type: String, value: Int)
        fun onBypassChange(bypass: Boolean)
    }
    
    private var listener: Listener? = null
    
    fun setListener(l: Listener) { listener = l }

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 2) {
        controlPort(DrumSymbol.MIX) {
            floatType {
                default = 0.7f
                get { _mix }
                set {
                    _mix = it.coerceIn(0f, 1f)
                    val baseGain = 1.6f
                    val finalGain = baseGain * it
                    drumGainLeft.inputB.set(finalGain.toDouble())
                    drumGainRight.inputB.set(finalGain.toDouble())
                }
            }
        }
        
        controlPort(DrumSymbol.BYPASS) {
            boolType {
                default = true
                get { _bypass }
                set {
                    _bypass = it
                    listener?.onBypassChange(it)
                }
            }
        }
        
        // BD
        controlPort(DrumSymbol.BD_FREQ) {
            floatType { get { frequencies[0] }; set { setParameters(0, it, tones[0], decays[0], p4s[0], p5s[0]) } }
        }
        controlPort(DrumSymbol.BD_TONE) {
            floatType { get { tones[0] }; set { setParameters(0, frequencies[0], it, decays[0], p4s[0], p5s[0]) } }
        }
        controlPort(DrumSymbol.BD_DECAY) {
            floatType { get { decays[0] }; set { setParameters(0, frequencies[0], tones[0], it, p4s[0], p5s[0]) } }
        }
        controlPort(DrumSymbol.BD_P4) {
            floatType { get { p4s[0] }; set { setParameters(0, frequencies[0], tones[0], decays[0], it, p5s[0]) } }
        }
        controlPort(DrumSymbol.BD_P5) {
            floatType { get { p5s[0] }; set { setParameters(0, frequencies[0], tones[0], decays[0], p4s[0], it) } }
        }
        
        // SD
        controlPort(DrumSymbol.SD_FREQ) {
            floatType { get { frequencies[1] }; set { setParameters(1, it, tones[1], decays[1], p4s[1], p5s[1]) } }
        }
        controlPort(DrumSymbol.SD_TONE) {
            floatType { get { tones[1] }; set { setParameters(1, frequencies[1], it, decays[1], p4s[1], p5s[1]) } }
        }
        controlPort(DrumSymbol.SD_DECAY) {
            floatType { get { decays[1] }; set { setParameters(1, frequencies[1], tones[1], it, p4s[1], p5s[1]) } }
        }
        controlPort(DrumSymbol.SD_P4) {
            floatType { get { p4s[1] }; set { setParameters(1, frequencies[1], tones[1], decays[1], it, p5s[1]) } }
        }
        
        // HH
        controlPort(DrumSymbol.HH_FREQ) {
            floatType { get { frequencies[2] }; set { setParameters(2, it, tones[2], decays[2], p4s[2], p5s[2]) } }
        }
        controlPort(DrumSymbol.HH_TONE) {
            floatType { get { tones[2] }; set { setParameters(2, frequencies[2], it, decays[2], p4s[2], p5s[2]) } }
        }
        controlPort(DrumSymbol.HH_DECAY) {
            floatType { get { decays[2] }; set { setParameters(2, frequencies[2], tones[2], it, p4s[2], p5s[2]) } }
        }
        controlPort(DrumSymbol.HH_P4) {
            floatType { get { p4s[2] }; set { setParameters(2, frequencies[2], tones[2], decays[2], it, p5s[2]) } }
        }
        
        // Routing
        controlPort(DrumSymbol.BD_TRIGGER_SRC) {
            intType { get { triggerSources[0] }; set { triggerSources[0] = it; listener?.onRoutingChange(0, "trigger", it) } }
        }
        controlPort(DrumSymbol.BD_PITCH_SRC) {
            intType { get { pitchSources[0] }; set { pitchSources[0] = it; listener?.onRoutingChange(0, "pitch", it) } }
        }
        
        controlPort(DrumSymbol.SD_TRIGGER_SRC) {
            intType { get { triggerSources[1] }; set { triggerSources[1] = it; listener?.onRoutingChange(1, "trigger", it) } }
        }
        controlPort(DrumSymbol.SD_PITCH_SRC) {
            intType { get { pitchSources[1] }; set { pitchSources[1] = it; listener?.onRoutingChange(1, "pitch", it) } }
        }
        
        controlPort(DrumSymbol.HH_TRIGGER_SRC) {
            intType { get { triggerSources[2] }; set { triggerSources[2] = it; listener?.onRoutingChange(2, "trigger", it) } }
        }
        controlPort(DrumSymbol.HH_PITCH_SRC) {
            intType { get { pitchSources[2] }; set { pitchSources[2] = it; listener?.onRoutingChange(2, "pitch", it) } }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 1; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = listOf(
        drumUnit, drumGainLeft, drumGainRight
    )
    
    override val outputs: Map<String, AudioOutput> = mapOf(
        "outputLeft" to drumGainLeft.output,
        "outputRight" to drumGainRight.output
    )
    
    override val inputs: Map<String, AudioInput> = mapOf(
        "triggerBD" to drumUnit.triggerInputBd,
        "triggerSD" to drumUnit.triggerInputSd,
        "triggerHH" to drumUnit.triggerInputHh
    )

    override fun initialize() {
        drumUnit.output.connect(drumGainLeft.inputA)
        drumUnit.output.connect(drumGainRight.inputA)
        
        portDefs.setValue(DrumSymbol.MIX, PortValue.FloatValue(_mix))
        
        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Legacy setter for backward compatibility
    fun setMix(value: Float) = portDefs.setValue(DrumSymbol.MIX, PortValue.FloatValue(value))
    fun getMix(): Float = _mix

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
    
    // Setters for syncing
    fun setRouting(drumIndex: Int, type: String, value: Int) {
        if (type == "trigger") triggerSources[drumIndex] = value
        if (type == "pitch") pitchSources[drumIndex] = value
    }
    
    fun setBypass(bypass: Boolean) { _bypass = bypass }
}
