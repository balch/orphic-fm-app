package org.balch.orpheus.plugins.pulsar

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class PulsarPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Pulsar",
        author = "Orpheus"
    )

    companion object {
        const val URI = PULSAR_URI
    }

    private var _playing = 0
    private var _vibeGeneration = 0
    private var _energy = 0.5f
    private var _complexity = 0.3f
    private var _space = 0.4f
    private var _mood = 0.5f
    private var _bpm = 128.0f
    private var _delaySend = 0.0f
    private var _reverbSend = 0.0f
    private var _rootNote = 2  // D
    private var _scaleIndex = 0  // Minor
    private var _mix = 0.0f
    private var _percMix = 0.7f
    private var _envelopeMode = 0  // 0=AD, 1=Tides, 2=Blend (energy-driven)
    private var _seed = 0
    private var _lickMutation = 0.5f
    private var _trackEdm = intArrayOf(21, 22, 23, 9, 14, 14, 17, 20)
    private var _trackSpace = intArrayOf(20, 17, 23, 19, 6, 14, 17, 19)
    private var _trackVolume = floatArrayOf(0.9f, 0.6f, 0.65f, 0.75f, 0.55f, 0.4f, 0.3f, 0.25f)
    private var _trackPan = floatArrayOf(0f, -0.15f, 0.2f, 0f, -0.25f, -0.35f, 0.3f, 0.4f)
    private var _trackHarmonics = FloatArray(8) { 0.5f }
    private var _trackTimbre = FloatArray(8) { 0.5f }
    private var _trackMorph = FloatArray(8) { 0.3f }
    private var _trackEnvelope = IntArray(8) { 0 }
    private var _trackPercussive = intArrayOf(1, 1, 1, 0, 0, 0, 0, 0)
    private var _trackMacro = FloatArray(8 * 16) { 0.5f }
    private var _genreDensity = floatArrayOf(0.5f, 0.35f, 0.8f, 0.4f, 0.3f, 0.2f, 0.15f, 0.08f)
    private var _genreSwing = 0.02f
    private var _genreGhostProb = 0.3f
    private var _genreNoteRangeLow = 36
    private var _genreNoteRangeHigh = 72
    private var _genreRhythmPattern = 3
    private var _lickLength = 0
    private var _lickData = FloatArray(96) { 0f }

    private val portDefs = ports(startIndex = 0) {
        controlPort(PulsarSymbol.PLAYING) {
            intType { default = 0; get { _playing }; set { _playing = it } }
        }
        controlPort(PulsarSymbol.VIBE_GENERATION) {
            intType { default = 0; get { _vibeGeneration }; set { _vibeGeneration = it } }
        }
        controlPort(PulsarSymbol.ENERGY) {
            floatType { default = 0.5f; get { _energy }; set { _energy = it } }
        }
        controlPort(PulsarSymbol.COMPLEXITY) {
            floatType { default = 0.3f; get { _complexity }; set { _complexity = it } }
        }
        controlPort(PulsarSymbol.SPACE) {
            floatType { default = 0.4f; get { _space }; set { _space = it } }
        }
        controlPort(PulsarSymbol.MOOD) {
            floatType { default = 0.5f; get { _mood }; set { _mood = it } }
        }
        controlPort(PulsarSymbol.BPM) {
            floatType { default = 128.0f; min = 40f; max = 300f; get { _bpm }; set { _bpm = it } }
        }
        controlPort(PulsarSymbol.DELAY_SEND) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _delaySend }; set { _delaySend = it } }
        }
        controlPort(PulsarSymbol.REVERB_SEND) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _reverbSend }; set { _reverbSend = it } }
        }
        controlPort(PulsarSymbol.ROOT_NOTE) {
            intType { default = 2; get { _rootNote }; set { _rootNote = it } }
        }
        controlPort(PulsarSymbol.SCALE) {
            intType { default = 0; get { _scaleIndex }; set { _scaleIndex = it } }
        }
        controlPort(PulsarSymbol.MIX) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _mix }; set { _mix = it } }
        }
        controlPort(PulsarSymbol.PERC_MIX) {
            floatType { default = 0.7f; min = 0f; max = 1f; get { _percMix }; set { _percMix = it } }
        }
        controlPort(PulsarSymbol.ENVELOPE_MODE) {
            intType { default = 0; get { _envelopeMode }; set { _envelopeMode = it } }
        }
        controlPort(PulsarSymbol.SEED) {
            intType { default = 0; get { _seed }; set { _seed = it } }
        }
        controlPort(PulsarSymbol.LICK_MUTATION) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _lickMutation }; set { _lickMutation = it } }
        }

        // Per-track engine selectors
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_EDM.ordinal + t * 2]) {
                intType { default = _trackEdm[t]; get { _trackEdm[t] }; set { _trackEdm[t] = it } }
            }
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_SPACE.ordinal + t * 2]) {
                intType { default = _trackSpace[t]; get { _trackSpace[t] }; set { _trackSpace[t] = it } }
            }
        }

        // Per-track voice params (each param type is 8 consecutive entries)
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_VOLUME.ordinal + t]) {
                floatType { default = _trackVolume[t]; min = 0f; max = 1f; get { _trackVolume[t] }; set { _trackVolume[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PAN.ordinal + t]) {
                floatType { default = _trackPan[t]; min = -1f; max = 1f; get { _trackPan[t] }; set { _trackPan[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS.ordinal + t]) {
                floatType { default = 0.5f; min = 0f; max = 1f; get { _trackHarmonics[t] }; set { _trackHarmonics[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_TIMBRE.ordinal + t]) {
                floatType { default = 0.5f; min = 0f; max = 1f; get { _trackTimbre[t] }; set { _trackTimbre[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MORPH.ordinal + t]) {
                floatType { default = 0.3f; min = 0f; max = 1f; get { _trackMorph[t] }; set { _trackMorph[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENVELOPE.ordinal + t]) {
                intType { default = _trackEnvelope[t]; get { _trackEnvelope[t] }; set { _trackEnvelope[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PERCUSSIVE.ordinal + t]) {
                intType { default = _trackPercussive[t]; get { _trackPercussive[t] }; set { _trackPercussive[t] = it } }
            }
        }

        // Per-track macro maps (16 entries per track, stride = 16)
        for (t in 0..7) {
            val macroBase = PulsarSymbol.TRACK_0_MACRO_ENERGY_VOL_MIN.ordinal + t * 16
            for (m in 0..15) {
                val idx = t * 16 + m
                controlPort(PulsarSymbol.entries[macroBase + m]) {
                    floatType { default = 0.5f; min = 0f; max = 1f; get { _trackMacro[idx] }; set { _trackMacro[idx] = it } }
                }
            }
        }

        // Genre profile
        for (i in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.GENRE_DENSITY_0.ordinal + i]) {
                floatType { default = _genreDensity[i]; min = 0f; max = 1f; get { _genreDensity[i] }; set { _genreDensity[i] = it } }
            }
        }
        controlPort(PulsarSymbol.GENRE_SWING) {
            floatType { default = 0.02f; min = 0f; max = 1f; get { _genreSwing }; set { _genreSwing = it } }
        }
        controlPort(PulsarSymbol.GENRE_GHOST_PROB) {
            floatType { default = 0.3f; min = 0f; max = 1f; get { _genreGhostProb }; set { _genreGhostProb = it } }
        }
        controlPort(PulsarSymbol.GENRE_NOTE_RANGE_LOW) {
            intType { default = 36; get { _genreNoteRangeLow }; set { _genreNoteRangeLow = it } }
        }
        controlPort(PulsarSymbol.GENRE_NOTE_RANGE_HIGH) {
            intType { default = 72; get { _genreNoteRangeHigh }; set { _genreNoteRangeHigh = it } }
        }
        controlPort(PulsarSymbol.GENRE_RHYTHM_PATTERN) {
            intType { default = 3; get { _genreRhythmPattern }; set { _genreRhythmPattern = it } }
        }

        // Lick buffer
        controlPort(PulsarSymbol.LICK_LENGTH) {
            intType { default = 0; get { _lickLength }; set { _lickLength = it } }
        }
        for (i in 0..95) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.LICK_DATA_0.ordinal + i]) {
                floatType { default = 0f; get { _lickData[i] }; set { _lickData[i] = it } }
            }
        }
    }

    override val ports: List<Port> = portDefs.ports

    override fun initialize() {}
    override fun onStart() {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
