package org.balch.orpheus.plugins.drum

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
 * Exhaustive enum of all Drum plugin port symbols.
 */
enum class DrumSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MIX("mix", "Mix")
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

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 2) {
        float(DrumSymbol.MIX) {
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

    private val audioPorts = listOf(
        AudioPort(0, "out_l", "Output Left", false),
        AudioPort(1, "out_r", "Output Right", false)
    )

    override val ports: List<Port> = audioPorts + portDefs.ports

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
}
