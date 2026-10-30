package org.balch.orpheus.plugins.duolfo

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.PATCH_CELLS
import org.balch.orpheus.core.audio.patch.PATCH_URI
import org.balch.orpheus.core.audio.patch.cellAmounts
import org.balch.orpheus.core.audio.patch.legacyDuoPatch
import org.balch.orpheus.core.audio.patch.patchCellSymbol
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
@ContributesBinding(AppScope::class, binding = binding<DuoPatchBay>())
class VoicePlugin(
    private val audioEngine: AudioEngine
) : DspPlugin, DuoPatchBay {

    override val info = PluginInfo(
        uri = URI,
        name = "Voice Engine",
        author = "Orpheus"
    )

    private val log = logging("VoicePlugin")

    companion object {
        const val URI = VOICE_URI
        const val MODULATION_URI = "org.balch.orpheus.plugins.modulation"
    }

    // Listeners for engine updates
    interface Listener {
        fun onVoiceParamChange(index: Int, param: String, value: Any)
        fun onGlobalParamChange(param: String, value: Any)
    }
    
    private var listener: Listener? = null
    
    fun setListener(l: Listener) {
        listener = l
    }

    // State storage matches DspSynthEngine defaults
    // Used for get/set via ports
    private val _tune = FloatArray(12) { 0.5f } // Default from SynthPreset is usually determined by patch
    private val _modDepth = FloatArray(12)
    private val _envSpeed = FloatArray(12)
    private val _duoSharpness = FloatArray(6)
    
    private var _fmStructureCrossQuad = false
    private var _totalFeedback = 0f
    private var _vibrato = 0f
    private var _coupling = 0f
    
    private val _quadPitch = FloatArray(3) { 0.5f }
    private val _quadHold = FloatArray(3)
    private val _quadVolume = FloatArray(3) { 1.0f }
    private val _quadTriggerSource = IntArray(3)
    private val _quadPitchSource = IntArray(3)
    private val _quadEnvTriggerMode = BooleanArray(3)
    private val _duoEngine = IntArray(6) { -1 }  // -1 = OSC mode (OrpheusEngineId.OSC.id)
    private val _duoHarmonics = FloatArray(6) { 0.0f }
    private val _duoProsody = FloatArray(6) { 0.5f }
    private val _duoSpeed = FloatArray(6) { 0.0f }
    private val _duoMorph = FloatArray(6) { 0.0f }
    private val _duoModSourceLevel = FloatArray(6) { 0.0f }

    // Missing data converts as VOICE_FM, so that is the app default too.
    private val _patches = MutableStateFlow(List(6) { legacyDuoPatch(LegacyModSource.VOICE_FM, false, it) })
    override val patches: StateFlow<List<DuoPatch>> = _patches.asStateFlow()

    // Cell amounts C++ last received per duo; C++ starts all zero.
    private val lastSent = Array(6) { FloatArray(PATCH_CELLS) }
    private val patchLock = SynchronizedObject()

    private val portDefs = ports(startIndex = 0) {
        // Voice Params (0-11)
        for (i in 0 until 12) {
            controlPort(VoiceSymbol.tune(i)) {
                floatType {
                    default = 0.5f
                    get { _tune[i] }
                    set {
                        if (_tune[i] != it) {
                            _tune[i] = it
                            listener?.onVoiceParamChange(i, "tune", it)
                        }
                    }
                }
            }
            controlPort(VoiceSymbol.modDepth(i)) {
                floatType {
                    default = 0f
                    get { _modDepth[i] }
                    set {
                        if (_modDepth[i] != it) {
                            _modDepth[i] = it
                            listener?.onVoiceParamChange(i, "mod_depth", it)
                        }
                    }
                }
            }
            controlPort(VoiceSymbol.envSpeed(i)) {
                floatType {
                    default = 0f
                    get { _envSpeed[i] }
                    set {
                        if (_envSpeed[i] != it) {
                            _envSpeed[i] = it
                            listener?.onVoiceParamChange(i, "env_speed", it)
                        }
                    }
                }
            }
        }

        // Duo Params (0-5)
        for (i in 0 until 6) {
            controlPort(VoiceSymbol.duoSharpness(i)) {
                floatType {
                    default = 0f
                    get { _duoSharpness[i] }
                    set {
                        _duoSharpness[i] = it
                        listener?.onVoiceParamChange(i, "duo_sharpness", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoEngine(i)) {
                intType {
                    default = -1
                    // Sparse OrpheusEngineId.id space: -1 (OSC), 0..23 (Plaits), 100..108 (Braids).
                    // min/max here are advisory metadata only — the setter does not clamp,
                    // and not every value in [min, max] is a valid id.
                    min = -1; max = 108
                    get { _duoEngine[i] }
                    set {
                        updateEngine(i, it)
                        log.info { "VoicePlugin.duoEngine[$i] = $it (listener=${listener != null})" }
                        listener?.onVoiceParamChange(i, "duo_engine", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoHarmonics(i)) {
                floatType {
                    default = 0.0f
                    get { _duoHarmonics[i] }
                    set {
                        _duoHarmonics[i] = it
                        listener?.onVoiceParamChange(i, "duo_harmonics", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoProsody(i)) {
                floatType {
                    default = 0.5f
                    get { _duoProsody[i] }
                    set {
                        _duoProsody[i] = it
                        listener?.onVoiceParamChange(i, "duo_prosody", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoSpeed(i)) {
                floatType {
                    default = 0.0f
                    get { _duoSpeed[i] }
                    set {
                        _duoSpeed[i] = it
                        listener?.onVoiceParamChange(i, "duo_speed", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoMorph(i)) {
                floatType {
                    default = 0.0f
                    get { _duoMorph[i] }
                    set {
                        _duoMorph[i] = it
                        listener?.onVoiceParamChange(i, "duo_morph", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoModSourceLevel(i)) {
                floatType {
                    default = 0.0f
                    get { _duoModSourceLevel[i] }
                    set {
                        _duoModSourceLevel[i] = it
                        listener?.onVoiceParamChange(i, "duo_mod_source_level", it)
                    }
                }
            }
        }

        // Global
        controlPort(VoiceSymbol.FM_STRUCTURE_CROSS_QUAD) {
            boolType {
                get { _fmStructureCrossQuad }
                set {
                    if (_fmStructureCrossQuad != it) {
                        _fmStructureCrossQuad = it
                        listener?.onGlobalParamChange("fm_structure", it)
                    }
                }
            }
        }
        controlPort(VoiceSymbol.TOTAL_FEEDBACK) {
            floatType {
                default = 0f
                get { _totalFeedback }
                set {
                    if (_totalFeedback != it) {
                        _totalFeedback = it
                        listener?.onGlobalParamChange("total_feedback", it)
                    }
                }
            }
        }
        controlPort(VoiceSymbol.VIBRATO) {
            floatType {
                default = 0f
                get { _vibrato }
                set {
                    if (_vibrato != it) {
                        _vibrato = it
                        listener?.onGlobalParamChange("vibrato", it)
                    }
                }
            }
        }
        controlPort(VoiceSymbol.COUPLING) {
            floatType {
                default = 0f
                get { _coupling }
                set {
                    if (_coupling != it) {
                        _coupling = it
                        listener?.onGlobalParamChange("coupling", it)
                    }
                }
            }
        }
        
        // Quad Params (0-2)
        for (i in 0 until 3) {
            controlPort(VoiceSymbol.quadPitch(i)) {
                floatType {
                    default = 0.5f
                    get { _quadPitch[i] }
                    set {
                        _quadPitch[i] = it
                        listener?.onVoiceParamChange(i, "quad_pitch", it)
                    }
                }
            }
            controlPort(VoiceSymbol.quadHold(i)) {
                floatType {
                    default = 0f
                    get { _quadHold[i] }
                    set {
                        _quadHold[i] = it
                        listener?.onVoiceParamChange(i, "quad_hold", it)
                    }
                }
            }
            controlPort(VoiceSymbol.quadVolume(i)) {
                floatType {
                    default = 1.0f
                    get { _quadVolume[i] }
                    set {
                        _quadVolume[i] = it
                        listener?.onVoiceParamChange(i, "quad_volume", it)
                    }
                }
            }
            controlPort(VoiceSymbol.quadTriggerSource(i)) {
                intType {
                    get { _quadTriggerSource[i] }
                    set {
                        _quadTriggerSource[i] = it
                        listener?.onVoiceParamChange(i, "quad_trigger_source", it)
                    }
                }
            }
            controlPort(VoiceSymbol.quadPitchSource(i)) {
                intType {
                    get { _quadPitchSource[i] }
                    set {
                        _quadPitchSource[i] = it
                        listener?.onVoiceParamChange(i, "quad_pitch_source", it)
                    }
                }
            }
            controlPort(VoiceSymbol.quadEnvTriggerMode(i)) {
                boolType {
                    get { _quadEnvTriggerMode[i] }
                    set {
                        _quadEnvTriggerMode[i] = it
                        listener?.onVoiceParamChange(i, "quad_env_trigger_mode", it)
                    }
                }
            }
        }
    }

    override val ports: List<Port> = portDefs.ports

    override fun initialize() {}
    override fun onStart() {}
    
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
    
    // Direct getters for native bridge sync
    fun getDuoEngine(i: Int): Int = _duoEngine[i]
    fun getDuoHarmonics(i: Int): Float = _duoHarmonics[i]
    fun getDuoSharpness(i: Int): Float = _duoSharpness[i]
    fun getDuoMorph(i: Int): Float = _duoMorph[i]
    fun getEnvSpeed(i: Int): Float = _envSpeed[i]
    fun getDuoProsody(i: Int): Float = _duoProsody[i]
    fun getDuoSpeed(i: Int): Float = _duoSpeed[i]

    // Direct getters/setters for DspSynthEngine sync (updates state ONLY, no listener trigger)
    fun setTune(i: Int, v: Float) { _tune[i] = v }
    fun setModDepth(i: Int, v: Float) { _modDepth[i] = v }
    fun setEnvSpeed(i: Int, v: Float) { _envSpeed[i] = v }
    fun setDuoSharpness(i: Int, v: Float) { _duoSharpness[i] = v }
    fun setDuoEngine(i: Int, v: Int) = updateEngine(i, v)
    fun setDuoHarmonics(i: Int, v: Float) { _duoHarmonics[i] = v }
    fun setDuoProsody(i: Int, v: Float) { _duoProsody[i] = v }
    fun setDuoSpeed(i: Int, v: Float) { _duoSpeed[i] = v }
    fun setDuoMorph(i: Int, v: Float) { _duoMorph[i] = v }
    fun setFmStructure(v: Boolean) { _fmStructureCrossQuad = v }
    fun setVibrato(v: Float) { _vibrato = v }
    fun setCoupling(v: Float) { _coupling = v }
    
    fun setQuadPitch(i: Int, v: Float) { _quadPitch[i] = v }
    fun setQuadHold(i: Int, v: Float) { _quadHold[i] = v }
    fun setQuadVolume(i: Int, v: Float) { _quadVolume[i] = v }
    fun setQuadTriggerSource(i: Int, v: Int) { _quadTriggerSource[i] = v }
    fun setQuadPitchSource(i: Int, v: Int) { _quadPitchSource[i] = v }
    fun setQuadEnvTriggerMode(i: Int, v: Boolean) { _quadEnvTriggerMode[i] = v }

    /** Every engine write lands here so DX arrivals park HARMONICS cables and departures wake them. */
    private fun updateEngine(duo: Int, id: Int) = synchronized(patchLock) {
        val old = _duoEngine[duo]
        _duoEngine[duo] = id
        if (old != id) {
            val patch = _patches.value[duo]
            val moved = patch.onEngineChange(OrpheusEngineId.fromId(old), OrpheusEngineId.fromId(id))
            if (moved != patch) setPatch(duo, moved)
        }
    }

    override fun engineOf(duo: Int): OrpheusEngineId? = OrpheusEngineId.fromId(_duoEngine[duo])

    override fun setPatch(duo: Int, patch: DuoPatch) = synchronized(patchLock) {
        _patches.value = _patches.value.toMutableList().also { it[duo] = patch }
        val cells = patch.cellAmounts()
        val sent = lastSent[duo]
        for (c in 0 until PATCH_CELLS) {
            if (cells[c] != sent[c]) {
                sent[c] = cells[c]
                audioEngine.setPort(PATCH_URI, patchCellSymbol(duo, c), cells[c])
            }
        }
    }

    override fun update(duo: Int, transform: (DuoPatch) -> DuoPatch) = synchronized(patchLock) {
        setPatch(duo, transform(_patches.value[duo]))
    }

    override fun applyLegacySource(duo: Int, source: LegacyModSource) = synchronized(patchLock) {
        setPatch(duo, legacyDuoPatch(source, engineOf(duo)?.forcePinHarmonics == true, duo))
    }

    /** Sends every cell of every duo. Only for a fresh native engine; preset syncs rely on the diff. */
    fun resyncPatches() = synchronized(patchLock) {
        _patches.value.forEachIndexed { duo, patch ->
            val cells = patch.cellAmounts()
            cells.copyInto(lastSent[duo])
            for (c in 0 until PATCH_CELLS) audioEngine.setPort(PATCH_URI, patchCellSymbol(duo, c), cells[c])
        }
    }

    fun setDuoModSourceLevel(duoIndex: Int, level: Float) {
        _duoModSourceLevel[duoIndex] = level
        audioEngine.setPort(URI, "duo_mod_source_level_$duoIndex", level)
    }

    fun setTotalFeedback(amount: Float) {
        _totalFeedback = amount
        audioEngine.setPort(URI, "total_feedback", amount)
    }

    fun setCouplingDepth(amount: Float) {
        audioEngine.setPort(URI, "coupling_depth", amount)
    }

    fun setFmCrossQuad(crossQuad: Boolean) {
        audioEngine.setPort(MODULATION_URI, "fm_cross_quad", if (crossQuad) 1f else 0f)
    }
}
