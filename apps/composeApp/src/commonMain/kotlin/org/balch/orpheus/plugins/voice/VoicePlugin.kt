package org.balch.orpheus.plugins.voice

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.PortSymbol
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports

enum class VoiceSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    // Global
    FM_STRUCTURE_CROSS_QUAD("fm_structure_cross_quad", "FM Cross Quad"),
    TOTAL_FEEDBACK("total_feedback", "Total Feedback"),
    VIBRATO("vibrato", "Vibrato"),
    COUPLING("coupling", "Voice Coupling")
}

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class VoicePlugin : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.voice",
        name = "Voice Engine",
        author = "Orpheus"
    )

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
    private val _pairSharpness = FloatArray(6)
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

    private val portDefs = ports(startIndex = 0) {
        // Voice Params (0-7 + extras)
        for (i in 0 until 12) {
            float("tune_$i") {
                default = 0.5f
                get { _tune[i] }
                set { 
                    if (_tune[i] != it) {
                        _tune[i] = it
                        listener?.onVoiceParamChange(i, "tune", it)
                    }
                }
            }
            float("mod_depth_$i") {
                get { _modDepth[i] }
                set {
                    if (_modDepth[i] != it) {
                        _modDepth[i] = it
                        listener?.onVoiceParamChange(i, "mod_depth", it)
                    }
                }
            }
            float("env_speed_$i") {
                get { _envSpeed[i] }
                set {
                    if (_envSpeed[i] != it) {
                        _envSpeed[i] = it
                        listener?.onVoiceParamChange(i, "env_speed", it)
                    }
                }
            }
        }
        
        // Pair Params (0-5)
        for (i in 0 until 6) {
            float("pair_sharpness_$i") {
                get { _pairSharpness[i] }
                set {
                    _pairSharpness[i] = it
                    listener?.onVoiceParamChange(i, "sharpness", it)
                }
            }
            int("duo_mod_source_$i") {
                get { _duoModSource[i] }
                set {
                    _duoModSource[i] = it
                    listener?.onVoiceParamChange(i, "duo_mod_source", it)
                }
            }
        }
        
        // Global
        bool(VoiceSymbol.FM_STRUCTURE_CROSS_QUAD) {
            get { _fmStructureCrossQuad }
            set {
                if (_fmStructureCrossQuad != it) {
                    _fmStructureCrossQuad = it
                    listener?.onGlobalParamChange("fm_structure", it)
                }
            }
        }
        float(VoiceSymbol.TOTAL_FEEDBACK) {
            get { _totalFeedback }
            set {
                if (_totalFeedback != it) {
                    _totalFeedback = it
                    listener?.onGlobalParamChange("total_feedback", it)
                }
            }
        }
        float(VoiceSymbol.VIBRATO) {
            get { _vibrato }
            set {
                if (_vibrato != it) {
                    _vibrato = it
                    listener?.onGlobalParamChange("vibrato", it)
                }
            }
        }
        float(VoiceSymbol.COUPLING) {
            get { _coupling }
            set {
                if (_coupling != it) {
                    _coupling = it
                    listener?.onGlobalParamChange("coupling", it)
                }
            }
        }
        
        // Quad Params (0-2)
        for (i in 0 until 3) {
            float("quad_pitch_$i") {
                default = 0.5f
                get { _quadPitch[i] }
                set {
                    _quadPitch[i] = it
                    listener?.onVoiceParamChange(i, "quad_pitch", it)
                }
            }
            float("quad_hold_$i") {
                get { _quadHold[i] }
                set {
                    _quadHold[i] = it
                    listener?.onVoiceParamChange(i, "quad_hold", it)
                }
            }
            float("quad_volume_$i") {
                default = 1.0f
                get { _quadVolume[i] }
                set {
                    _quadVolume[i] = it
                    listener?.onVoiceParamChange(i, "quad_volume", it)
                }
            }
            int("quad_trigger_source_$i") {
                get { _quadTriggerSource[i] }
                set {
                    _quadTriggerSource[i] = it
                    listener?.onVoiceParamChange(i, "quad_trigger_source", it)
                }
            }
            int("quad_pitch_source_$i") {
                get { _quadPitchSource[i] }
                set {
                    _quadPitchSource[i] = it
                    listener?.onVoiceParamChange(i, "quad_pitch_source", it)
                }
            }
            bool("quad_env_trigger_mode_$i") {
                get { _quadEnvTriggerMode[i] }
                set {
                    _quadEnvTriggerMode[i] = it
                    listener?.onVoiceParamChange(i, "quad_env_trigger_mode", it)
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
    fun setPairSharpness(i: Int, v: Float) { _pairSharpness[i] = v }
    fun setDuoModSource(i: Int, v: Int) { _duoModSource[i] = v }
    
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
