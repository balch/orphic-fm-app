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
    private var _deep = 0.0f
    private var _pulsarDelayTimeA = 0.3f
    private var _pulsarDelayTimeB = 0.35f
    private var _pulsarDelayFeedback = 0.4f
    private var _pulsarDelayDamping = 0.5f
    private var _pulsarReverbSize = 0.6f
    private var _pulsarReverbDamping = 0.5f
    private var _pulsarReverbBrightness = 0.5f
    private var _rootNote = 2  // D
    private var _scaleIndex = 0  // Minor
    private var _mix = 1.0f
    private var _percMix = 0.7f
    private var _envelopeMode = 0  // 0=AD, 1=Tides, 2=Blend (energy-driven)
    private var _seed = 0
    private var _lickMutation = 0.5f
    private var _lickOctave = -1  // -1 = auto (midpoint of noteRange)
    private var _trackEdm = intArrayOf(21, 22, 23, 9, 14, 14, 17, 20)
    private var _trackSpace = intArrayOf(20, 17, 23, 19, 6, 14, 17, 19)
    private var _trackVolume = floatArrayOf(0.9f, 0.6f, 0.65f, 0.75f, 0.55f, 0.4f, 0.3f, 0.25f)
    private var _trackPan = floatArrayOf(0f, -0.15f, 0.2f, 0f, -0.25f, -0.35f, 0.3f, 0.4f)
    private var _trackHarmonics = FloatArray(8) { 0.5f }
    private var _trackTimbre = FloatArray(8) { 0.5f }
    private var _trackMorph = FloatArray(8) { 0.3f }
    private var _trackEnvelope = IntArray(8) { 0 }
    private var _trackRole = IntArray(8) { if (it < 3) 0 else 1 }
    private val _trackBarStrategy = IntArray(8) { 0 }
    private var _stepCount = 16
    private var _trackMacro = FloatArray(8 * 16) { 0.5f }
    private var _genreDensity = floatArrayOf(0.5f, 0.35f, 0.8f, 0.4f, 0.3f, 0.2f, 0.15f, 0.08f)
    private var _genreSwing = 0.02f
    private var _genreGhostProb = 0.3f
    private var _genreNoteRangeLow = 36
    private var _genreNoteRangeHigh = 72
    private var _genreRhythmDensity = 1.0f
    private var _genreProgressionStyle = 0
    private var _genreChordsPerBar = 2
    private var _progressionAnchor = 4  // default EVERY_4 = 4 bars
    private var _progressionDriftRange = 0.5f
    private var _lickLength = 0
    private var _lickLoopLength = 0
    private var _lickData = FloatArray(96) { 0f }
    // Tension profile
    private var _tensionInnerBars = 4
    private var _tensionOuterBars = 0
    private var _tensionOuterDepth = 0.5f
    private var _tensionVolume = 0.3f
    private var _tensionTiming = 0.2f
    private var _tensionOctaveShift = 0
    private var _tensionKeyShift = 0
    private var _tensionHalfLick = 0
    private var _tensionChromaticPassing = 0.0f
    private var _tensionEvoTimbreLow = 0.25f
    private var _tensionEvoTimbreHigh = 0.55f
    private var _tensionEvoTimbreProb = 0.7f
    private var _tensionEvoMorphLow = -1.0f
    private var _tensionEvoMorphHigh = -1.0f
    private var _tensionEvoMorphProb = 0.5f
    private var _tensionEvoHarmLow = -1.0f
    private var _tensionEvoHarmHigh = -1.0f
    private var _tensionEvoHarmProb = 0.3f
    private var _tensionEvoAttackPoint = 0.5f
    private var _tensionEvoReleaseSpeed = 0.3f
    private var _trackEvoWeight = floatArrayOf(-1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f)
    private val _modLfoRate = FloatArray(8) { 0.2f }
    private val _modLfoDepth = FloatArray(8) { 0f }
    private val _modLfoShape = FloatArray(8) { 0.3f }
    private val _modLfoCoupling = FloatArray(8) { 0.2f }
    private val _holdProbability = FloatArray(8) { 0f }
    private val _holdLengthMin = IntArray(8) { 2 }
    private val _holdLengthMax = IntArray(8) { 8 }
    private val _trackDelaySend = FloatArray(8) { 0f }
    private val _trackReverbSend = FloatArray(8) { 0f }
    private val _noteRangeLow = IntArray(8) { 0 }
    private val _noteRangeHigh = IntArray(8) { 0 }
    private val _reverbBrightness = FloatArray(8) { 0.5f }
    private val _densityOverride = FloatArray(8) { -1f }
    private val _delayFeedbackTrack = FloatArray(8) { -1f }
    private val _glideRate = FloatArray(8) { 0f }
    private val _trackLickMode = IntArray(8) { 0 }
    private val _trackEvoRhythmic = IntArray(8) { 0 }
    private val _trackEvoTensionResp = FloatArray(8) { 1.0f }
    private val _trackEvoNoteFollow = IntArray(8) { 0 }
    private val _trackEvoPitchMode = IntArray(8) { 0 }
    private val _trackEvoVoicingTension = FloatArray(8) { 1.0f }
    private val _trackCompingStyle = IntArray(8) { 0 }  // 0 = PAD
    private val _trackArpMode = IntArray(8) { 0 }           // AUTO
    private val _trackArpSpeed = FloatArray(8) { 0.2f }
    private val _trackArpDirection = IntArray(8) { 0 }      // UP
    private val _trackInversion = IntArray(8) { 0 }         // FOLLOW_STYLE
    private val _trackHumanDropProb = FloatArray(8) { 0.0f }
    private val _trackHumanGhostProb = FloatArray(8) { 0.0f }
    private val _trackHumanOctaveProb = FloatArray(8) { 0.0f }
    private val _trackHumanExtProb = FloatArray(8) { 0.0f }
    private val _trackFillEveryN = IntArray(8) { 0 }
    private val _trackFillType = IntArray(8) { 1 }  // ASCENDING_ARP
    private val _trackFillSkipProb = FloatArray(8) { 0.0f }
    private val _trackChordFollow = IntArray(8) { 0 }  // default FOLLOW
    // LPG: default 3 = LPG_ENGINE_DEFAULT (per-engine table). Decay/colour 0.5 mid.
    private val _trackLpgMode      = IntArray(8) { 3 }
    private val _trackLpgModeSpace = IntArray(8) { 3 }
    private val _trackLpgDecay     = FloatArray(8) { 0.5f }
    private val _trackLpgColour    = FloatArray(8) { 0.5f }

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
        controlPort(PulsarSymbol.DEEP) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _deep }; set { _deep = it } }
        }
        controlPort(PulsarSymbol.PULSAR_DELAY_TIME_A) {
            floatType { default = 0.3f; min = 0f; max = 1f; get { _pulsarDelayTimeA }; set { _pulsarDelayTimeA = it } }
        }
        controlPort(PulsarSymbol.PULSAR_DELAY_TIME_B) {
            floatType { default = 0.35f; min = 0f; max = 1f; get { _pulsarDelayTimeB }; set { _pulsarDelayTimeB = it } }
        }
        controlPort(PulsarSymbol.PULSAR_DELAY_FEEDBACK) {
            floatType { default = 0.4f; min = 0f; max = 1f; get { _pulsarDelayFeedback }; set { _pulsarDelayFeedback = it } }
        }
        controlPort(PulsarSymbol.PULSAR_DELAY_DAMPING) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _pulsarDelayDamping }; set { _pulsarDelayDamping = it } }
        }
        controlPort(PulsarSymbol.PULSAR_REVERB_SIZE) {
            floatType { default = 0.6f; min = 0f; max = 1f; get { _pulsarReverbSize }; set { _pulsarReverbSize = it } }
        }
        controlPort(PulsarSymbol.PULSAR_REVERB_DAMPING) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _pulsarReverbDamping }; set { _pulsarReverbDamping = it } }
        }
        controlPort(PulsarSymbol.PULSAR_REVERB_BRIGHTNESS) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _pulsarReverbBrightness }; set { _pulsarReverbBrightness = it } }
        }
        controlPort(PulsarSymbol.ROOT_NOTE) {
            intType { default = 2; get { _rootNote }; set { _rootNote = it } }
        }
        controlPort(PulsarSymbol.SCALE) {
            intType { default = 0; get { _scaleIndex }; set { _scaleIndex = it } }
        }
        controlPort(PulsarSymbol.MIX) {
            floatType { default = 1.0f; min = 0f; max = 1f; get { _mix }; set { _mix = it } }
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
        controlPort(PulsarSymbol.LICK_OCTAVE) {
            intType { default = -1; get { _lickOctave }; set { _lickOctave = it } }
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
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ROLE.ordinal + t]) {
                intType { default = _trackRole[t]; get { _trackRole[t] }; set { _trackRole[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_BAR_STRATEGY.ordinal + t]) {
                intType { default = _trackBarStrategy[t]; get { _trackBarStrategy[t] }; set { _trackBarStrategy[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_RHYTHMIC.ordinal + t]) {
                intType { default = 0; get { _trackEvoRhythmic[t] }; set { _trackEvoRhythmic[t] = it } }
            }
        }
        controlPort(PulsarSymbol.STEP_COUNT) {
            intType { default = _stepCount; get { _stepCount }; set { _stepCount = it } }
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
        controlPort(PulsarSymbol.GENRE_RHYTHM_DENSITY) {
            floatType { default = 1.0f; get { _genreRhythmDensity }; set { _genreRhythmDensity = it } }
        }
        controlPort(PulsarSymbol.GENRE_PROGRESSION_STYLE) {
            intType { default = 0; get { _genreProgressionStyle }; set { _genreProgressionStyle = it } }
        }
        controlPort(PulsarSymbol.GENRE_CHORDS_PER_BAR) {
            intType { default = 2; get { _genreChordsPerBar }; set { _genreChordsPerBar = it } }
        }
        controlPort(PulsarSymbol.PROGRESSION_ANCHOR) {
            intType { default = 4; get { _progressionAnchor }; set { _progressionAnchor = it } }
        }
        controlPort(PulsarSymbol.PROGRESSION_DRIFT_RANGE) {
            floatType { default = 0.5f; min = 0f; max = 1f
                get { _progressionDriftRange }; set { _progressionDriftRange = it } }
        }

        // Lick buffer
        controlPort(PulsarSymbol.LICK_LENGTH) {
            intType { default = 0; get { _lickLength }; set { _lickLength = it } }
        }
        controlPort(PulsarSymbol.LICK_LOOP_LENGTH) {
            intType { default = 0; get { _lickLoopLength }; set { _lickLoopLength = it } }
        }
        for (i in 0..95) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.LICK_DATA_0.ordinal + i]) {
                floatType { default = 0f; get { _lickData[i] }; set { _lickData[i] = it } }
            }
        }

        // Tension profile
        controlPort(PulsarSymbol.TENSION_INNER_BARS) {
            intType { default = 4; get { _tensionInnerBars }; set { _tensionInnerBars = it } }
        }
        controlPort(PulsarSymbol.TENSION_OUTER_BARS) {
            intType { default = 0; get { _tensionOuterBars }; set { _tensionOuterBars = it } }
        }
        controlPort(PulsarSymbol.TENSION_OUTER_DEPTH) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _tensionOuterDepth }; set { _tensionOuterDepth = it } }
        }
        controlPort(PulsarSymbol.TENSION_VOLUME) {
            floatType { default = 0.3f; min = 0f; max = 1f; get { _tensionVolume }; set { _tensionVolume = it } }
        }
        controlPort(PulsarSymbol.TENSION_TIMING) {
            floatType { default = 0.2f; min = 0f; max = 1f; get { _tensionTiming }; set { _tensionTiming = it } }
        }
        controlPort(PulsarSymbol.TENSION_OCTAVE_SHIFT) {
            intType { default = 0; get { _tensionOctaveShift }; set { _tensionOctaveShift = it } }
        }
        controlPort(PulsarSymbol.TENSION_KEY_SHIFT) {
            intType { default = 0; get { _tensionKeyShift }; set { _tensionKeyShift = it } }
        }
        controlPort(PulsarSymbol.TENSION_HALF_LICK) {
            intType { default = 0; get { _tensionHalfLick }; set { _tensionHalfLick = it } }
        }
        controlPort(PulsarSymbol.TENSION_CHROMATIC_PASSING) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _tensionChromaticPassing }; set { _tensionChromaticPassing = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_TIMBRE_LOW) {
            floatType { default = 0.25f; min = -1f; max = 1f; get { _tensionEvoTimbreLow }; set { _tensionEvoTimbreLow = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_TIMBRE_HIGH) {
            floatType { default = 0.55f; min = -1f; max = 1f; get { _tensionEvoTimbreHigh }; set { _tensionEvoTimbreHigh = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_TIMBRE_PROB) {
            floatType { default = 0.7f; min = 0f; max = 1f; get { _tensionEvoTimbreProb }; set { _tensionEvoTimbreProb = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_MORPH_LOW) {
            floatType { default = -1.0f; min = -1f; max = 1f; get { _tensionEvoMorphLow }; set { _tensionEvoMorphLow = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_MORPH_HIGH) {
            floatType { default = -1.0f; min = -1f; max = 1f; get { _tensionEvoMorphHigh }; set { _tensionEvoMorphHigh = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_MORPH_PROB) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _tensionEvoMorphProb }; set { _tensionEvoMorphProb = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_HARM_LOW) {
            floatType { default = -1.0f; min = -1f; max = 1f; get { _tensionEvoHarmLow }; set { _tensionEvoHarmLow = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_HARM_HIGH) {
            floatType { default = -1.0f; min = -1f; max = 1f; get { _tensionEvoHarmHigh }; set { _tensionEvoHarmHigh = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_HARM_PROB) {
            floatType { default = 0.3f; min = 0f; max = 1f; get { _tensionEvoHarmProb }; set { _tensionEvoHarmProb = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_ATTACK_POINT) {
            floatType { default = 0.5f; min = 0f; max = 1f; get { _tensionEvoAttackPoint }; set { _tensionEvoAttackPoint = it } }
        }
        controlPort(PulsarSymbol.TENSION_EVO_RELEASE_SPEED) {
            floatType { default = 0.3f; min = 0f; max = 1f; get { _tensionEvoReleaseSpeed }; set { _tensionEvoReleaseSpeed = it } }
        }
        // Per-track evolution weight
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_WEIGHT.ordinal + t]) {
                floatType { default = -1f; min = -1f; max = 1f; get { _trackEvoWeight[t] }; set { _trackEvoWeight[t] = it } }
            }
        }

        // Per-track mod LFO parameters
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_RATE.ordinal + t]) {
                floatType { default = 0.2f; min = 0.01f; max = 2f; get { _modLfoRate[t] }; set { _modLfoRate[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_DEPTH.ordinal + t]) {
                floatType { default = 0f; min = 0f; max = 1f; get { _modLfoDepth[t] }; set { _modLfoDepth[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_SHAPE.ordinal + t]) {
                floatType { default = 0.3f; min = 0f; max = 1f; get { _modLfoShape[t] }; set { _modLfoShape[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_COUPLING.ordinal + t]) {
                floatType { default = 0.2f; min = 0f; max = 1f; get { _modLfoCoupling[t] }; set { _modLfoCoupling[t] = it } }
            }
        }

        // Per-track hold parameters
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_PROBABILITY.ordinal + t]) {
                floatType { default = 0f; min = 0f; max = 1f; get { _holdProbability[t] }; set { _holdProbability[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MIN.ordinal + t]) {
                intType { default = 2; min = 1; max = 16; get { _holdLengthMin[t] }; set { _holdLengthMin[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MAX.ordinal + t]) {
                intType { default = 8; min = 1; max = 16; get { _holdLengthMax[t] }; set { _holdLengthMax[t] = it } }
            }
        }

        // Per-track effect sends
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_SEND.ordinal + t]) {
                floatType { default = 0f; min = 0f; max = 1f; get { _trackDelaySend[t] }; set { _trackDelaySend[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_SEND_TRACK.ordinal + t]) {
                floatType { default = 0f; min = 0f; max = 1f; get { _trackReverbSend[t] }; set { _trackReverbSend[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_LOW.ordinal + t]) {
                intType { default = 0; min = 0; max = 127; get { _noteRangeLow[t] }; set { _noteRangeLow[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_HIGH.ordinal + t]) {
                intType { default = 0; min = 0; max = 127; get { _noteRangeHigh[t] }; set { _noteRangeHigh[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_BRIGHTNESS.ordinal + t]) {
                floatType { default = 0.5f; min = 0f; max = 1f; get { _reverbBrightness[t] }; set { _reverbBrightness[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DENSITY_OVERRIDE.ordinal + t]) {
                floatType { default = -1f; min = -1f; max = 1f; get { _densityOverride[t] }; set { _densityOverride[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_FEEDBACK_TRACK.ordinal + t]) {
                floatType { default = -1f; min = -1f; max = 0.95f; get { _delayFeedbackTrack[t] }; set { _delayFeedbackTrack[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_GLIDE_RATE.ordinal + t]) {
                floatType { default = 0f; min = 0f; max = 1f; get { _glideRate[t] }; set { _glideRate[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LICK_MODE.ordinal + t]) {
                intType { default = 0; get { _trackLickMode[t] }; set { _trackLickMode[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_TENSION_RESP.ordinal + t]) {
                floatType { default = 1.0f; min = 0f; max = 1f; get { _trackEvoTensionResp[t] }; set { _trackEvoTensionResp[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_NOTE_FOLLOW.ordinal + t]) {
                intType { default = 0; get { _trackEvoNoteFollow[t] }; set { _trackEvoNoteFollow[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_PITCH_MODE.ordinal + t]) {
                intType { default = 0; get { _trackEvoPitchMode[t] }; set { _trackEvoPitchMode[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_VOICING_TENSION.ordinal + t]) {
                floatType { default = 1.0f; min = 0f; max = 1f; get { _trackEvoVoicingTension[t] }; set { _trackEvoVoicingTension[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_COMPING_STYLE.ordinal + t]) {
                intType { default = 0; get { _trackCompingStyle[t] }; set { _trackCompingStyle[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_MODE.ordinal + t]) {
                intType { default = 0; get { _trackArpMode[t] }; set { _trackArpMode[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_SPEED.ordinal + t]) {
                floatType { default = 0.2f; min = 0f; max = 1f; get { _trackArpSpeed[t] }; set { _trackArpSpeed[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_DIRECTION.ordinal + t]) {
                intType { default = 0; get { _trackArpDirection[t] }; set { _trackArpDirection[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_INVERSION.ordinal + t]) {
                intType { default = 0; get { _trackInversion[t] }; set { _trackInversion[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_DROP_PROB.ordinal + t]) {
                floatType { default = 0.0f; get { _trackHumanDropProb[t] }; set { _trackHumanDropProb[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_GHOST_PROB.ordinal + t]) {
                floatType { default = 0.0f; get { _trackHumanGhostProb[t] }; set { _trackHumanGhostProb[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_OCTAVE_PROB.ordinal + t]) {
                floatType { default = 0.0f; get { _trackHumanOctaveProb[t] }; set { _trackHumanOctaveProb[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_EXT_PROB.ordinal + t]) {
                floatType { default = 0.0f; get { _trackHumanExtProb[t] }; set { _trackHumanExtProb[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_EVERY_N.ordinal + t]) {
                intType { default = 0; get { _trackFillEveryN[t] }; set { _trackFillEveryN[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_TYPE.ordinal + t]) {
                intType { default = 1; get { _trackFillType[t] }; set { _trackFillType[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_SKIP_PROB.ordinal + t]) {
                floatType { default = 0.0f; get { _trackFillSkipProb[t] }; set { _trackFillSkipProb[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_CHORD_FOLLOW.ordinal + t]) {
                intType { default = 0; get { _trackChordFollow[t] }; set { _trackChordFollow[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_MODE.ordinal + t]) {
                intType { default = 3; get { _trackLpgMode[t] }; set { _trackLpgMode[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_MODE_SPACE.ordinal + t]) {
                intType { default = 3; get { _trackLpgModeSpace[t] }; set { _trackLpgModeSpace[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_DECAY.ordinal + t]) {
                floatType { default = 0.5f; min = 0f; max = 1f
                    get { _trackLpgDecay[t] }; set { _trackLpgDecay[t] = it } }
            }
        }
        for (t in 0..7) {
            controlPort(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_COLOUR.ordinal + t]) {
                floatType { default = 0.5f; min = 0f; max = 1f
                    get { _trackLpgColour[t] }; set { _trackLpgColour[t] = it } }
            }
        }
    }

    override val ports: List<Port> = portDefs.ports

    override fun initialize() {}
    override fun onStart() {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
