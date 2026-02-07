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
import org.balch.orpheus.core.audio.dsp.PlaitsUnit
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DRUM_URI
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.plugins.drum.engine.DrumEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId

/**
 * DSP Plugin for drum synthesis with selectable Plaits engines per slot.
 *
 * Uses 3 independent [PlaitsUnit] instances (BD/SD/HH), each of which can
 * host any [PlaitsEngine]. Outputs are summed through a mixer to stereo gain stages.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DrumPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
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
        /** Per-slot output gain for balanced mix */
        private val SLOT_GAINS = floatArrayOf(1.2f, 0.6f, 0.5f)
    }

    private val engineFactory = DrumEngineFactory()

    // 3 independent PlaitsUnit slots
    private val slots: Array<PlaitsUnit> = Array(3) { dspFactory.createPlaitsUnit() }

    // Mixer: slot outputs → add chain → gain
    private val mixer01 = dspFactory.createAdd()   // BD + SD
    private val mixer012 = dspFactory.createAdd()   // (BD+SD) + HH

    // Per-slot gain multipliers
    private val slotGains = Array(3) { dspFactory.createMultiply() }

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
            floatType { get { frequencies[0] }; set { updateSlotParam(0, freq = it) } }
        }
        controlPort(DrumSymbol.BD_TONE) {
            floatType { get { tones[0] }; set { updateSlotParam(0, tone = it) } }
        }
        controlPort(DrumSymbol.BD_DECAY) {
            floatType { get { decays[0] }; set { updateSlotParam(0, decay = it) } }
        }
        controlPort(DrumSymbol.BD_P4) {
            floatType { get { p4s[0] }; set { updateSlotParam(0, p4 = it) } }
        }
        controlPort(DrumSymbol.BD_P5) {
            floatType { get { p5s[0] }; set { updateSlotParam(0, p5 = it) } }
        }

        // SD
        controlPort(DrumSymbol.SD_FREQ) {
            floatType { get { frequencies[1] }; set { updateSlotParam(1, freq = it) } }
        }
        controlPort(DrumSymbol.SD_TONE) {
            floatType { get { tones[1] }; set { updateSlotParam(1, tone = it) } }
        }
        controlPort(DrumSymbol.SD_DECAY) {
            floatType { get { decays[1] }; set { updateSlotParam(1, decay = it) } }
        }
        controlPort(DrumSymbol.SD_P4) {
            floatType { get { p4s[1] }; set { updateSlotParam(1, p4 = it) } }
        }

        // HH
        controlPort(DrumSymbol.HH_FREQ) {
            floatType { get { frequencies[2] }; set { updateSlotParam(2, freq = it) } }
        }
        controlPort(DrumSymbol.HH_TONE) {
            floatType { get { tones[2] }; set { updateSlotParam(2, tone = it) } }
        }
        controlPort(DrumSymbol.HH_DECAY) {
            floatType { get { decays[2] }; set { updateSlotParam(2, decay = it) } }
        }
        controlPort(DrumSymbol.HH_P4) {
            floatType { get { p4s[2] }; set { updateSlotParam(2, p4 = it) } }
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

    override val audioUnits: List<AudioUnit> = listOf(
        slots[0], slots[1], slots[2],
        slotGains[0], slotGains[1], slotGains[2],
        mixer01, mixer012,
        drumGainLeft, drumGainRight
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "outputLeft" to drumGainLeft.output,
        "outputRight" to drumGainRight.output
    )

    override val inputs: Map<String, AudioInput> = mapOf(
        "triggerBD" to slots[0].triggerInput,
        "triggerSD" to slots[1].triggerInput,
        "triggerHH" to slots[2].triggerInput
    )

    override fun initialize() {
        // Install default engines
        for (i in 0..2) {
            val engine = engineFactory.create(DEFAULT_ENGINES[i])
            slots[i].setEngine(engine)
        }

        // Wire: slot[i].output → slotGain[i].inputA, with static gain on inputB
        for (i in 0..2) {
            slots[i].output.connect(slotGains[i].inputA)
            slotGains[i].inputB.set(SLOT_GAINS[i].toDouble())
        }

        // Mix: slotGain[0] + slotGain[1] → mixer01
        slotGains[0].output.connect(mixer01.inputA)
        slotGains[1].output.connect(mixer01.inputB)

        // Mix: mixer01 + slotGain[2] → mixer012
        mixer01.output.connect(mixer012.inputA)
        slotGains[2].output.connect(mixer012.inputB)

        // Stereo gain
        mixer012.output.connect(drumGainLeft.inputA)
        mixer012.output.connect(drumGainRight.inputA)

        portDefs.setValue(DrumSymbol.MIX, PortValue.FloatValue(_mix))

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

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
        applyParamsToSlot(type)
        slots[type].trigger(accent)
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
        applyParamsToSlot(type)
    }

    fun trigger(type: Int, accent: Float) {
        if (type !in 0..2) return
        applyParamsToSlot(type)
        slots[type].trigger(accent)
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

    private fun updateSlotParam(
        slot: Int,
        freq: Float = frequencies[slot],
        tone: Float = tones[slot],
        decay: Float = decays[slot],
        p4: Float = p4s[slot],
        p5: Float = p5s[slot]
    ) {
        frequencies[slot] = freq
        tones[slot] = tone
        decays[slot] = decay
        p4s[slot] = p4
        p5s[slot] = p5
        applyParamsToSlot(slot)
    }

    private fun applyParamsToSlot(slot: Int) {
        // Convert 0..1 frequency to MIDI note range appropriate for the slot's default range
        val note = frequencyToNote(slot, frequencies[slot])
        slots[slot].setNote(note)
        slots[slot].setTimbre(tones[slot])
        slots[slot].setMorph(decays[slot])
        slots[slot].setHarmonics(p4s[slot])
    }

    private fun frequencyToNote(slot: Int, freq01: Float): Float {
        // Map 0..1 to the MIDI note range appropriate for this drum slot
        return when (slot) {
            0 -> 28f + freq01 * 24f  // BD: ~55Hz-220Hz → MIDI 28-52
            1 -> 48f + freq01 * 24f  // SD: ~130Hz-520Hz → MIDI 48-72
            2 -> 60f + freq01 * 24f  // HH: ~260Hz-1040Hz → MIDI 60-84
            else -> 60f
        }
    }

    private fun setSlotEngine(slot: Int, engineOrdinal: Int) {
        val entries = PlaitsEngineId.entries
        if (engineOrdinal !in entries.indices) return
        engineIds[slot] = engineOrdinal
        val engineId = entries[engineOrdinal]
        val engine = engineFactory.create(engineId)
        slots[slot].setEngine(engine)
    }
}
