package org.balch.orpheus.plugins.drum

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.DRUM_URI
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.plugins.plaits.PlaitsEngineId

/**
 * DSP Plugin for drum synthesis with selectable Plaits engines per slot.
 *
 * Pure state container — C++ handles all audio processing.
 * Keeps `audioEngine` for `triggerDrum()` and `setPort()` forwarding.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DrumPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Drum Machine",
        author = "Balch"
    )

    companion object {
        const val URI = DRUM_URI
        /** Default engines for each slot */
        val DEFAULT_ENGINES = arrayOf(
            PlaitsEngineId.ANALOG_BASS_DRUM,
            PlaitsEngineId.ANALOG_SNARE_DRUM,
            PlaitsEngineId.METALLIC_HI_HAT
        )
    }

    // Internal state
    private var _mix = 0.7f
    // Musical defaults: BD low, SD mid, HH high (matching ViewModel)
    private val frequencies = floatArrayOf(0.3f, 0.4f, 0.6f)
    private val tones = FloatArray(3) { 0.5f }
    private val decays = FloatArray(3) { 0.5f }
    private val p4s = FloatArray(3) { 0.5f }
    private val p5s = FloatArray(3) { 0.5f }
    private val engineIds = IntArray(3) { i -> DEFAULT_ENGINES[i].ordinal }

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
                set { _mix = it.coerceIn(0f, 1f) }
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
            floatType { get { frequencies[0] }; set { frequencies[0] = it } }
        }
        controlPort(DrumSymbol.BD_TONE) {
            floatType { get { tones[0] }; set { tones[0] = it } }
        }
        controlPort(DrumSymbol.BD_DECAY) {
            floatType { get { decays[0] }; set { decays[0] = it } }
        }
        controlPort(DrumSymbol.BD_P4) {
            floatType { get { p4s[0] }; set { p4s[0] = it } }
        }
        controlPort(DrumSymbol.BD_P5) {
            floatType { get { p5s[0] }; set { p5s[0] = it } }
        }

        // SD
        controlPort(DrumSymbol.SD_FREQ) {
            floatType { get { frequencies[1] }; set { frequencies[1] = it } }
        }
        controlPort(DrumSymbol.SD_TONE) {
            floatType { get { tones[1] }; set { tones[1] = it } }
        }
        controlPort(DrumSymbol.SD_DECAY) {
            floatType { get { decays[1] }; set { decays[1] = it } }
        }
        controlPort(DrumSymbol.SD_P4) {
            floatType { get { p4s[1] }; set { p4s[1] = it } }
        }

        // HH
        controlPort(DrumSymbol.HH_FREQ) {
            floatType { get { frequencies[2] }; set { frequencies[2] = it } }
        }
        controlPort(DrumSymbol.HH_TONE) {
            floatType { get { tones[2] }; set { tones[2] = it } }
        }
        controlPort(DrumSymbol.HH_DECAY) {
            floatType { get { decays[2] }; set { decays[2] = it } }
        }
        controlPort(DrumSymbol.HH_P4) {
            floatType { get { p4s[2] }; set { p4s[2] = it } }
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

        // Engine selection
        controlPort(DrumSymbol.BD_ENGINE) {
            intType {
                default = DEFAULT_ENGINES[0].ordinal
                min = 0; max = PlaitsEngineId.entries.size - 1
                options = PlaitsEngineId.entries.map { it.displayName }
                get { engineIds[0] }
                set { setSlotEngine(0, it) }
            }
        }
        controlPort(DrumSymbol.SD_ENGINE) {
            intType {
                default = DEFAULT_ENGINES[1].ordinal
                min = 0; max = PlaitsEngineId.entries.size - 1
                options = PlaitsEngineId.entries.map { it.displayName }
                get { engineIds[1] }
                set { setSlotEngine(1, it) }
            }
        }
        controlPort(DrumSymbol.HH_ENGINE) {
            intType {
                default = DEFAULT_ENGINES[2].ordinal
                min = 0; max = PlaitsEngineId.entries.size - 1
                options = PlaitsEngineId.entries.map { it.displayName }
                get { engineIds[2] }
                set { setSlotEngine(2, it) }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 1; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // --- Public API for engine/trigger control ---

    fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        p4: Float = 0.5f,
        p5: Float = 0.5f
    ) {
        if (type !in 0..2) return
        frequencies[type] = frequency
        tones[type] = tone
        decays[type] = decay
        p4s[type] = p4
        p5s[type] = p5
        forwardTriggerNative(type, accent, frequency, tone, decay, p4, p5)
    }

    fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        p4: Float,
        p5: Float
    ) {
        if (type !in 0..2) return
        frequencies[type] = frequency
        tones[type] = tone
        decays[type] = decay
        p4s[type] = p4
        p5s[type] = p5
    }

    fun trigger(type: Int, accent: Float) {
        if (type !in 0..2) return
        audioEngine.triggerDrum(type, accent)
    }

    /** Forward drum routing gains. */
    fun setRouting(chainGain: Float, directGain: Float) {
        audioEngine.setPort(URI, "drum_chain_gain_l", chainGain)
        audioEngine.setPort(URI, "drum_chain_gain_r", chainGain)
        audioEngine.setPort(URI, "drum_direct_gain_l", directGain)
        audioEngine.setPort(URI, "drum_direct_gain_r", directGain)
    }

    /** Forward drum direct-resonator wet/dry gains. */
    fun setDirectResonatorGains(wet: Float, dry: Float) {
        audioEngine.setPort(URI, "drum_direct_reso_wet_l", wet)
        audioEngine.setPort(URI, "drum_direct_reso_wet_r", wet)
        audioEngine.setPort(URI, "drum_direct_reso_dry_l", dry)
        audioEngine.setPort(URI, "drum_direct_reso_dry_r", dry)
    }

    private fun forwardTriggerNative(type: Int, accent: Float,
                                     frequency: Float, tone: Float, decay: Float,
                                     p4: Float, p5: Float) {
        val prefix = when (type) { 0 -> "bd_"; 1 -> "sd_"; 2 -> "hh_"; else -> return }
        audioEngine.setPort(URI, "${prefix}freq", frequency)
        audioEngine.setPort(URI, "${prefix}tone", tone)
        audioEngine.setPort(URI, "${prefix}decay", decay)
        audioEngine.setPort(URI, "${prefix}p4", p4)
        audioEngine.setPort(URI, "${prefix}p5", p5)
        audioEngine.triggerDrum(type, accent)
    }

    // Getters for persistence
    fun getFrequency(type: Int) = frequencies.getOrElse(type) { 0.5f }
    fun getTone(type: Int) = tones.getOrElse(type) { 0.5f }
    fun getDecay(type: Int) = decays.getOrElse(type) { 0.5f }
    fun getP4(type: Int) = p4s.getOrElse(type) { 0.5f }
    fun getP5(type: Int) = p5s.getOrElse(type) { 0.5f }
    fun getEngineId(type: Int) = engineIds.getOrElse(type) { 0 }

    // Setters for syncing
    fun setRouting(drumIndex: Int, type: String, value: Int) {
        if (type == "trigger") triggerSources[drumIndex] = value
        if (type == "pitch") pitchSources[drumIndex] = value
    }

    fun setBypass(bypass: Boolean) { _bypass = bypass }

    // --- Private helpers ---

    private fun setSlotEngine(slot: Int, engineOrdinal: Int) {
        val entries = PlaitsEngineId.entries
        if (engineOrdinal !in entries.indices) return
        engineIds[slot] = engineOrdinal
    }
}
