package org.balch.orpheus.plugins.duolfo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class VoicePlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Voice Engine",
        author = "Orpheus"
    )

    companion object {
        const val URI = VOICE_URI
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
    private val _duoModSource = IntArray(6)
    
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
    private val _duoEngine = IntArray(6)
    private val _duoHarmonics = FloatArray(6) { 0.0f }
    private val _duoProsody = FloatArray(6) { 0.5f }
    private val _duoSpeed = FloatArray(6) { 0.0f }
    private val _duoMorph = FloatArray(6) { 0.0f }
    private val _duoModSourceLevel = FloatArray(6) { 0.0f }

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
            controlPort(VoiceSymbol.duoModSource(i)) {
                intType {
                    get { _duoModSource[i] }
                    set {
                        _duoModSource[i] = it
                        listener?.onVoiceParamChange(i, "duo_mod_source", it)
                    }
                }
            }
            controlPort(VoiceSymbol.duoEngine(i)) {
                intType {
                    default = 0
                    min = 0; max = 17
                    get { _duoEngine[i] }
                    set {
                        _duoEngine[i] = it
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
    override val audioUnits: List<AudioUnit> = emptyList() // Managed by DspSynthEngine
    override val inputs: Map<String, AudioInput> = emptyMap()
    override val outputs: Map<String, AudioOutput> = emptyMap()

    override fun initialize() {}
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}
    
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
    
    // Direct getters/setters for DspSynthEngine sync (updates state ONLY, no listener trigger)
    fun setTune(i: Int, v: Float) { _tune[i] = v }
    fun setModDepth(i: Int, v: Float) { _modDepth[i] = v }
    fun setEnvSpeed(i: Int, v: Float) { _envSpeed[i] = v }
    fun setDuoSharpness(i: Int, v: Float) { _duoSharpness[i] = v }
    fun setDuoModSource(i: Int, v: Int) { _duoModSource[i] = v }
    fun setDuoEngine(i: Int, v: Int) { _duoEngine[i] = v }
    fun setDuoHarmonics(i: Int, v: Float) { _duoHarmonics[i] = v }
    fun setDuoProsody(i: Int, v: Float) { _duoProsody[i] = v }
    fun setDuoSpeed(i: Int, v: Float) { _duoSpeed[i] = v }
    fun setDuoMorph(i: Int, v: Float) { _duoMorph[i] = v }
    fun setDuoModSourceLevel(i: Int, v: Float) { _duoModSourceLevel[i] = v }

    fun setFmStructure(v: Boolean) { _fmStructureCrossQuad = v }
    fun setTotalFeedback(v: Float) { _totalFeedback = v }
    fun setVibrato(v: Float) { _vibrato = v }
    fun setCoupling(v: Float) { _coupling = v }
    
    fun setQuadPitch(i: Int, v: Float) { _quadPitch[i] = v }
    fun setQuadHold(i: Int, v: Float) { _quadHold[i] = v }
    fun setQuadVolume(i: Int, v: Float) { _quadVolume[i] = v }
    fun setQuadTriggerSource(i: Int, v: Int) { _quadTriggerSource[i] = v }
    fun setQuadPitchSource(i: Int, v: Int) { _quadPitchSource[i] = v }
    fun setQuadEnvTriggerMode(i: Int, v: Boolean) { _quadEnvTriggerMode[i] = v }
}
