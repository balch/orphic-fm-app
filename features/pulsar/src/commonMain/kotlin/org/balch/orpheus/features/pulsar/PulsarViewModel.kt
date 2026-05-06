package org.balch.orpheus.features.pulsar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.AppSymbol
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.DuckingProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.PitchEvolution
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.SoloBehavior
import org.balch.orpheus.features.pulsar.models.SoloMarkovConfig
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.chordComping
import org.balch.orpheus.features.pulsar.models.chordFollow
import org.balch.orpheus.features.pulsar.models.lickMode
import kotlin.concurrent.Volatile

@Serializable
@Immutable
data class PulsarUiState(
    val playing: Boolean = false,
    val globalPaused: Boolean = true,
    val bpm: Float = 128f,
    val vibeName: String = "",
    val vibe: Vibe,
    val energy: Float = 0.5f,
    val complexity: Float = 0.3f,
    val space: Float = 0.4f,
    val mood: Float = 0.5f,
    val deep: Float = 0.0f,
    val rootNote: Int = 2,
    val scaleIndex: Int = 0,
    val mix: Float = 1.0f,
    val percMix: Float = 0.7f,
    val envelopeMode: Int = 0,  // 0=AD, 1=Tides, 2=Blend (energy-driven)
    val selectedTrack: Int? = null,
    val trackEnginesEdm: List<Int> = listOf(21, 22, 23, 9, 14, 14, 17, 20),
    val trackEnginesSpace: List<Int> = listOf(20, 17, 23, 19, 6, 14, 17, 19),
    val trackMuted: List<Boolean> = List(8) { false },
)

@Immutable
data class PulsarPanelActions(
    val setVibe: (Vibe) -> Unit = {},
    val setEnergy: (Float) -> Unit = {},
    val setComplexity: (Float) -> Unit = {},
    val setSpace: (Float) -> Unit = {},
    val setMood: (Float) -> Unit = {},
    val setBpm: (Float) -> Unit = {},
    val setDeep: (Float) -> Unit = {},
    val setRootNote: (Int) -> Unit = {},
    val setScale: (Int) -> Unit = {},
    val setMix: (Float) -> Unit = {},
    val setPercMix: (Float) -> Unit = {},
    val setEnvelopeMode: (Int) -> Unit = {},
    val selectTrack: (Int?) -> Unit = {},
    val setTrackEngineEdm: (Int, Int) -> Unit = { _, _ -> },
    val setTrackEngineSpace: (Int, Int) -> Unit = { _, _ -> },
    val toggleTrackMute: (Int) -> Unit = {},
) {
    companion object {
        val EMPTY = PulsarPanelActions()
    }
}

/** User intents for the Pulsar panel. */
private sealed interface PulsarIntent {
    data class Playing(val value: Boolean) : PulsarIntent
    data class GlobalPaused(val value: Boolean) : PulsarIntent
    data class VibeChange(val value: Vibe) : PulsarIntent
    data class Energy(val value: Float) : PulsarIntent
    data class Complexity(val value: Float) : PulsarIntent
    data class Space(val value: Float) : PulsarIntent
    data class Mood(val value: Float) : PulsarIntent
    data class Bpm(val value: Float) : PulsarIntent
    data class Deep(val value: Float) : PulsarIntent
    data class RootNote(val value: Int) : PulsarIntent
    data class Scale(val value: Int) : PulsarIntent
    data class Mix(val value: Float) : PulsarIntent
    data class PercMix(val value: Float) : PulsarIntent
    data class EnvelopeMode(val value: Int) : PulsarIntent
    data class SelectedTrack(val value: Int?) : PulsarIntent
    data class TrackEngineEdm(val track: Int, val engine: Int) : PulsarIntent
    data class TrackEngineSpace(val track: Int, val engine: Int) : PulsarIntent
    data class TrackMuteList(val muteList: List<Boolean>) : PulsarIntent
}

interface PulsarFeature : SynthFeature<PulsarUiState, PulsarPanelActions> {
    val vibeList: List<Vibe>
        get() = emptyList()  // default for previews

    val vibeFlow: kotlinx.coroutines.flow.StateFlow<Vibe>

    fun applyVibe(vibe: Vibe)

    val arrangementStateFlow: StateFlow<PulsarArrangementState>

    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.PULSAR
            override val title = "Pulsar"

            override val markdown = """
                Beat machine with vibe-based presets. Controls energy, complexity, space, and mood
                to shape rhythmic patterns generated by the Pulsar engine.

                ## Controls
                - **PLAY**: Start/stop the beat machine.
                - **BPM**: Tempo in beats per minute.
                - **VIBE**: Select a vibe preset (pushes full recipe to engine).
                - **ENERGY**: Intensity/velocity of the pattern.
                - **COMPLEXITY**: Rhythmic density and variation.
                - **SPACE**: Stereo width and reverb amount.
                - **MOOD**: Tonal character of the drum sounds.
                - **DEEP**: Overall send level to delay/reverb effects.
                - **BALANCE**: Slider to blend between delay (left) and reverb (right).
            """.trimIndent()

            override val portControlKeys = mapOf(
                PulsarSymbol.PLAYING.controlId.key to "Start/stop playback (0=stopped, 1=playing)",
                PulsarSymbol.ENERGY.controlId.key to "Pattern intensity (0..1)",
                PulsarSymbol.COMPLEXITY.controlId.key to "Rhythmic density (0..1)",
                PulsarSymbol.SPACE.controlId.key to "Stereo width and space (0..1)",
                PulsarSymbol.MOOD.controlId.key to "Tonal character (0..1)",
                PulsarSymbol.BPM.controlId.key to "Tempo in BPM (0..300)",
                PulsarSymbol.DEEP.controlId.key to "Effect send depth (0=dry, 1=full vibe sends)",
                PulsarSymbol.ROOT_NOTE.controlId.key to "Root note (0=C, 11=B)",
                PulsarSymbol.SCALE.controlId.key to "Scale index (0-5)",
                PulsarSymbol.MIX.controlId.key to "Output mix level (0..1)",
            )
        }
    }
}

/**
 * ViewModel for the Pulsar beat machine panel.
 *
 * Bridges PulsarSymbol controls to the C++ engine via SynthController,
 * exposing a reactive PulsarUiState and stable PulsarPanelActions.
 */
@OptIn(FlowPreview::class)
@Inject
@ClassKey
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class PulsarViewModel(
    private val synthController: SynthController,
    synthEngine: SynthEngine,
    private val globalTempo: GlobalTempo,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
    vibeProviders: Set<VibeProvider>,
    private val playbackMode: PulsarPlaybackMode,
) : PulsarFeature {

    override val vibeList: List<Vibe> = vibeProviders.map { it.vibe }.sortedBy { it.name }

    private val log = logging("PulsarVM")

    // ═══════════════════════════════════════════════════════════
    // Control flows
    // ═══════════════════════════════════════════════════════════
    private val playingId = synthController.controlFlow(PulsarSymbol.PLAYING.controlId)
    private val mutedId = synthController.controlFlow(AppSymbol.MUTED.controlId)
    private val vibeGenerationId = synthController.controlFlow(PulsarSymbol.VIBE_GENERATION.controlId)
    private val energyId = synthController.controlFlow(PulsarSymbol.ENERGY.controlId)
    private val complexityId = synthController.controlFlow(PulsarSymbol.COMPLEXITY.controlId)
    private val spaceId = synthController.controlFlow(PulsarSymbol.SPACE.controlId)
    private val moodId = synthController.controlFlow(PulsarSymbol.MOOD.controlId)
    private val bpmId = synthController.controlFlow(PulsarSymbol.BPM.controlId)
    private val deepId = synthController.controlFlow(PulsarSymbol.DEEP.controlId)
    private val pulsarDelayTimeAId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_TIME_A.controlId)
    private val pulsarDelayTimeBId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_TIME_B.controlId)
    private val pulsarDelayFeedbackId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_FEEDBACK.controlId)
    private val pulsarDelayDampingId = synthController.controlFlow(PulsarSymbol.PULSAR_DELAY_DAMPING.controlId)
    private val pulsarReverbSizeId = synthController.controlFlow(PulsarSymbol.PULSAR_REVERB_SIZE.controlId)
    private val pulsarReverbDampingId = synthController.controlFlow(PulsarSymbol.PULSAR_REVERB_DAMPING.controlId)
    private val pulsarReverbBrightnessId = synthController.controlFlow(PulsarSymbol.PULSAR_REVERB_BRIGHTNESS.controlId)
    private val rootNoteId = synthController.controlFlow(PulsarSymbol.ROOT_NOTE.controlId)
    private val scaleId = synthController.controlFlow(PulsarSymbol.SCALE.controlId)
    private val mixId = synthController.controlFlow(PulsarSymbol.MIX.controlId)
    private val percMixId = synthController.controlFlow(PulsarSymbol.PERC_MIX.controlId)
    private val envelopeModeId = synthController.controlFlow(PulsarSymbol.ENVELOPE_MODE.controlId)
    private val seedId = synthController.controlFlow(PulsarSymbol.SEED.controlId)
    private val lickMutationId = synthController.controlFlow(PulsarSymbol.LICK_MUTATION.controlId)
    private val lickOctaveId = synthController.controlFlow(PulsarSymbol.LICK_OCTAVE.controlId)
    private val trackEdmIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_EDM.ordinal + i * 2].controlId)
    }
    private val trackSpaceIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_SPACE.ordinal + i * 2].controlId)
    }

    // Write-only control flows for vibe recipe push
    private val trackVolumeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_VOLUME.ordinal + i].controlId)
    }
    private val trackPanIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PAN.ordinal + i].controlId)
    }
    private val trackHarmonicsIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS.ordinal + i].controlId)
    }
    private val trackTimbreIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_TIMBRE.ordinal + i].controlId)
    }
    private val trackMorphIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MORPH.ordinal + i].controlId)
    }
    private val trackEnvelopeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENVELOPE.ordinal + i].controlId)
    }
    private val trackRoleIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ROLE.ordinal + i].controlId)
    }
    private val trackBarStrategyIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_BAR_STRATEGY.ordinal + i].controlId)
    }
    private val trackEvoRhythmicIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_RHYTHMIC.ordinal + i].controlId)
    }
    private val stepCountId = synthController.controlFlow(PulsarSymbol.STEP_COUNT.controlId)
    private val trackMacroIds = (0..7).map { t ->
        (0..13).map { m ->
            synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MACRO_ENERGY_VOL_MIN.ordinal + t * 14 + m].controlId)
        }
    }
    private val genreDensityIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.GENRE_DENSITY_0.ordinal + i].controlId)
    }
    private val genreSwingId = synthController.controlFlow(PulsarSymbol.GENRE_SWING.controlId)
    private val genreGhostProbId = synthController.controlFlow(PulsarSymbol.GENRE_GHOST_PROB.controlId)
    private val genreNoteRangeLowId = synthController.controlFlow(PulsarSymbol.GENRE_NOTE_RANGE_LOW.controlId)
    private val genreNoteRangeHighId = synthController.controlFlow(PulsarSymbol.GENRE_NOTE_RANGE_HIGH.controlId)
    private val genreRhythmDensityId = synthController.controlFlow(PulsarSymbol.GENRE_RHYTHM_DENSITY.controlId)
    private val genreProgressionStyleId = synthController.controlFlow(PulsarSymbol.GENRE_PROGRESSION_STYLE.controlId)
    private val genreChordsPerBarId = synthController.controlFlow(PulsarSymbol.GENRE_CHORDS_PER_BAR.controlId)
    private val progressionAnchorId = synthController.controlFlow(PulsarSymbol.PROGRESSION_ANCHOR.controlId)
    private val progressionDriftRangeId = synthController.controlFlow(PulsarSymbol.PROGRESSION_DRIFT_RANGE.controlId)
    private val lickLengthId = synthController.controlFlow(PulsarSymbol.LICK_LENGTH.controlId)
    private val lickLoopLengthId = synthController.controlFlow(PulsarSymbol.LICK_LOOP_LENGTH.controlId)
    // 32 lick steps × 4 floats per step (degree, duration, velocity, glide_rate).
    private val lickDataIds = (0..127).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.LICK_DATA_0.ordinal + i].controlId)
    }

    // Tension control flows
    private val tensionInnerBarsId = synthController.controlFlow(PulsarSymbol.TENSION_INNER_BARS.controlId)
    private val tensionOuterBarsId = synthController.controlFlow(PulsarSymbol.TENSION_OUTER_BARS.controlId)
    private val tensionOuterDepthId = synthController.controlFlow(PulsarSymbol.TENSION_OUTER_DEPTH.controlId)
    private val tensionVolumeId = synthController.controlFlow(PulsarSymbol.TENSION_VOLUME.controlId)
    private val tensionTimingId = synthController.controlFlow(PulsarSymbol.TENSION_TIMING.controlId)
    private val tensionOctaveShiftId = synthController.controlFlow(PulsarSymbol.TENSION_OCTAVE_SHIFT.controlId)
    private val tensionKeyShiftId = synthController.controlFlow(PulsarSymbol.TENSION_KEY_SHIFT.controlId)
    private val tensionHalfLickId = synthController.controlFlow(PulsarSymbol.TENSION_HALF_LICK.controlId)
    private val tensionChromaticPassingId = synthController.controlFlow(PulsarSymbol.TENSION_CHROMATIC_PASSING.controlId)
    private val tensionEvoTimbreLowId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_TIMBRE_LOW.controlId)
    private val tensionEvoTimbreHighId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_TIMBRE_HIGH.controlId)
    private val tensionEvoTimbreProbId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_TIMBRE_PROB.controlId)
    private val tensionEvoMorphLowId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_MORPH_LOW.controlId)
    private val tensionEvoMorphHighId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_MORPH_HIGH.controlId)
    private val tensionEvoMorphProbId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_MORPH_PROB.controlId)
    private val tensionEvoHarmLowId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_HARM_LOW.controlId)
    private val tensionEvoHarmHighId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_HARM_HIGH.controlId)
    private val tensionEvoHarmProbId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_HARM_PROB.controlId)
    private val tensionEvoAttackPointId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_ATTACK_POINT.controlId)
    private val tensionEvoReleaseSpeedId = synthController.controlFlow(PulsarSymbol.TENSION_EVO_RELEASE_SPEED.controlId)
    private val tensionSpurtChanceId = synthController.controlFlow(PulsarSymbol.TENSION_SPURT_CHANCE.controlId)
    private val trackEvoWeightIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_WEIGHT.ordinal + i].controlId)
    }
    private val trackModLfoRateIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_RATE.ordinal + i].controlId)
    }
    private val trackModLfoDepthIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_DEPTH.ordinal + i].controlId)
    }
    private val trackModLfoShapeIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_SHAPE.ordinal + i].controlId)
    }
    private val trackModLfoCouplingIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_COUPLING.ordinal + i].controlId)
    }
    private val trackHoldProbabilityIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_PROBABILITY.ordinal + i].controlId)
    }
    private val trackHoldLengthMinIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MIN.ordinal + i].controlId)
    }
    private val trackHoldLengthMaxIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MAX.ordinal + i].controlId)
    }
    private val trackDelaySendIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_SEND.ordinal + i].controlId)
    }
    private val trackReverbSendIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_SEND_TRACK.ordinal + i].controlId)
    }
    private val trackNoteRangeLowIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_LOW.ordinal + it].controlId) }
    private val trackNoteRangeHighIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_HIGH.ordinal + it].controlId) }
    private val trackReverbBrightnessIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_BRIGHTNESS.ordinal + it].controlId) }
    private val trackDelayFeedbackIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_FEEDBACK_TRACK.ordinal + it].controlId) }
    private val trackGlideRateIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_GLIDE_RATE.ordinal + it].controlId) }
    private val trackLickModeIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LICK_MODE.ordinal + it].controlId) }
    private val trackCompingStyleIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_COMPING_STYLE.ordinal + it].controlId)
    }
    private val trackArpModeIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_MODE.ordinal + it].controlId)
    }
    private val trackArpSpeedIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_SPEED.ordinal + it].controlId)
    }
    private val trackArpDirectionIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ARP_DIRECTION.ordinal + it].controlId)
    }
    private val trackInversionIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_INVERSION.ordinal + it].controlId)
    }
    private val trackHumanDropIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_DROP_PROB.ordinal + it].controlId)
    }
    private val trackHumanGhostIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_GHOST_PROB.ordinal + it].controlId)
    }
    private val trackHumanOctaveIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_OCTAVE_PROB.ordinal + it].controlId)
    }
    private val trackHumanExtIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HUMAN_EXT_PROB.ordinal + it].controlId)
    }
    private val trackFillEveryNIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_EVERY_N.ordinal + it].controlId)
    }
    private val trackFillTypeIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_TYPE.ordinal + it].controlId)
    }
    private val trackFillSkipProbIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_FILL_SKIP_PROB.ordinal + it].controlId)
    }
    private val trackChordFollowIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_CHORD_FOLLOW.ordinal + it].controlId)
    }
    private val trackLpgModeIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_MODE.ordinal + it].controlId)
    }
    private val trackLpgModeSpaceIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_MODE_SPACE.ordinal + it].controlId)
    }
    private val trackLpgDecayIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_DECAY.ordinal + it].controlId)
    }
    private val trackLpgColourIds = (0..7).map {
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_COLOUR.ordinal + it].controlId)
    }
    // Per-engine Space variants — pushed alongside the EDM-side ports above.
    // C++ render path picks between EDM and Space atomics based on which engine
    // slot is active for each track in the current render block.
    private val trackVolumeSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_VOLUME_SPACE.ordinal + it].controlId) }
    private val trackHarmonicsSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HARMONICS_SPACE.ordinal + it].controlId) }
    private val trackTimbreSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_TIMBRE_SPACE.ordinal + it].controlId) }
    private val trackMorphSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MORPH_SPACE.ordinal + it].controlId) }
    private val trackModLfoRateSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_RATE_SPACE.ordinal + it].controlId) }
    private val trackModLfoDepthSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_DEPTH_SPACE.ordinal + it].controlId) }
    private val trackModLfoShapeSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_SHAPE_SPACE.ordinal + it].controlId) }
    private val trackModLfoCouplingSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MOD_LFO_COUPLING_SPACE.ordinal + it].controlId) }
    private val trackHoldProbabilitySpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_PROBABILITY_SPACE.ordinal + it].controlId) }
    private val trackHoldLengthMinSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MIN_SPACE.ordinal + it].controlId) }
    private val trackHoldLengthMaxSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_HOLD_LENGTH_MAX_SPACE.ordinal + it].controlId) }
    private val trackDelaySendSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_SEND_SPACE.ordinal + it].controlId) }
    private val trackReverbSendSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_SEND_SPACE.ordinal + it].controlId) }
    private val trackNoteRangeLowSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_LOW_SPACE.ordinal + it].controlId) }
    private val trackNoteRangeHighSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_NOTE_RANGE_HIGH_SPACE.ordinal + it].controlId) }
    private val trackReverbBrightnessSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_REVERB_BRIGHTNESS_SPACE.ordinal + it].controlId) }
    private val trackDelayFeedbackSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_DELAY_FEEDBACK_SPACE.ordinal + it].controlId) }
    private val trackGlideRateSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_GLIDE_RATE_SPACE.ordinal + it].controlId) }
    private val trackLpgDecaySpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_DECAY_SPACE.ordinal + it].controlId) }
    private val trackLpgColourSpaceIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_LPG_COLOUR_SPACE.ordinal + it].controlId) }

    private val trackEvoTensionRespIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_TENSION_RESP.ordinal + it].controlId) }
    private val trackEvoNoteFollowIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_NOTE_FOLLOW.ordinal + it].controlId) }
    private val trackEvoPitchModeIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_PITCH_MODE.ordinal + it].controlId) }
    private val trackEvoVoicingTensionIds = (0..7).map { synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_EVO_VOICING_TENSION.ordinal + it].controlId) }

    private val muteSymbols = listOf(
        PulsarSymbol.TRACK_0_MUTE, PulsarSymbol.TRACK_1_MUTE,
        PulsarSymbol.TRACK_2_MUTE, PulsarSymbol.TRACK_3_MUTE,
        PulsarSymbol.TRACK_4_MUTE, PulsarSymbol.TRACK_5_MUTE,
        PulsarSymbol.TRACK_6_MUTE, PulsarSymbol.TRACK_7_MUTE,
    )

    private val selectedTrackFlow = MutableStateFlow<Int?>(null)
    private val _trackMutedFlow = MutableStateFlow(List(8) { false })
    override val vibeFlow = MutableStateFlow(vibeList.first())
    private val restoreComplete = CompletableDeferred<Unit>()
    private val persistJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // Tracks the bpmMultiplier of the section we're currently in. Read/written
    // by the section-BPM collector and reset to 1.0f at every applyVibe (since
    // applyVibe resets globalTempo to vibe.bpm — the 1.0× baseline).
    @Volatile private var lastSectionMult: Float = 1.0f

    // Per-track effective send base values. Section overrides may swap these
    // between vibe load and the next vibe load — pushEffectiveSends reads from
    // here so per-section reverb/delay sends survive DEEP/SPACE knob updates.
    private val sendBaseDelay = FloatArray(8)
    private val sendBaseReverb = FloatArray(8)

    // Seed BPM from GlobalTempo before controlIntents/stateFlow subscribe,
    // so the first emission matches the system tempo, not the plugin default.
    init {
        bpmId.value = FloatValue(globalTempo.getBpm().toFloat())
        // Restore saved state on startup, then re-apply after every preset load
        // (since applyPreset() resets all ports including Pulsar).
        scope.launch(dispatcherProvider.io) {
            restoreSavedState()
            restoreComplete.complete(Unit)
            presetLoader.presetFlow.collect {
                restoreSavedState()
            }
        }
        // Re-apply vibe after engine graph loads: the initial restore runs before
        // nativeLoadGraph which resets all engine atomics. Await graphReady to ensure
        // the graph is loaded before re-pushing the vibe.
        scope.launch(dispatcherProvider.io) {
            restoreComplete.await()
            synthEngine.graphReady.await()
            if (vibeFlow.value.arrangement != null) {
                log.debug { "Re-applying vibe after engine init" }
                applyVibe(vibeFlow.value)
            }
        }
        // If the underlying C++ engine is recreated mid-session (Android: Oboe
        // rebuilds on audio route changes that shift sample rate), re-push the
        // current vibe recipe. DspSynthEngine handles graph + port-map sync;
        // the per-vibe track engines / harmonics / lick steps are NOT in the
        // port map, so without this re-push Pulsar would stay silent until
        // the user manually picks a vibe again.
        scope.launch(dispatcherProvider.io) {
            synthEngine.engineRecreatedFlow.collect {
                log.info { "Engine recreated — re-applying vibe ${vibeFlow.value.name}" }
                applyVibe(vibeFlow.value)
            }
        }
    }

    override val arrangementStateFlow: StateFlow<PulsarArrangementState> =
        synthEngine.pulsarArrangementStateFlow
            .filterNotNull()
            .map { state ->
                // Enrich with band member names if band solos are configured
                val vibe = vibeFlow.value
                val section = vibe.arrangement?.sections?.getOrNull(state.sectionIndex)
                val memberNames = vibe.band?.members?.map { it.name }
                val enriched = if (memberNames != null) {
                    state.copy(
                        bandSolo = true,
                        bandMemberNames = memberNames,
                    )
                } else state

                val sectionName = section?.name ?: "section-${state.sectionIndex}"
                if (enriched.soloActive && enriched.soloTrack >= 0) {
                    val name = if (enriched.bandSolo) {
                        enriched.bandMemberNames.getOrElse(enriched.soloTrack) { "?" }
                    } else {
                        PULSAR_TRACK_NAMES.getOrElse(enriched.soloTrack) { "?" }
                    }
                    log.debug { "Solo active: $name section=$sectionName" }
                } else {
                    log.debug { "Section: $sectionName bar=${state.barsElapsed}/${state.barsTotal}" }
                }
                enriched
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = sharingStrategy,
                initialValue = ARRANGEMENT_STATE_UNKNOWN
            )

    // ═══════════════════════════════════════════════════════════
    // Actions
    // ═══════════════════════════════════════════════════════════
    override val actions = PulsarPanelActions(
        setVibe = { vibe -> applyVibe(vibe) },
        setEnergy = energyId.floatSetter(),
        setComplexity = complexityId.floatSetter(),
        setSpace = { value ->
            spaceId.value = FloatValue(value)
            pushEffectiveSends(deepId.value.asFloat())
        },
        setMood = moodId.floatSetter(),
        setBpm = { bpm ->
            bpmId.value = FloatValue(bpm)
            globalTempo.setBpm(bpm.toDouble())
        },
        setDeep = { value ->
            deepId.value = FloatValue(value)
            pushEffectiveSends(value)
        },
        setRootNote = rootNoteId.intSetter(),
        setScale = scaleId.intSetter(),
        setMix = { value ->
            mixId.value = FloatValue(value)
        },
        setPercMix = percMixId.floatSetter(),
        setEnvelopeMode = envelopeModeId.intSetter(),
        selectTrack = { selectedTrackFlow.value = it },
        setTrackEngineEdm = { track, engine ->
            trackEdmIds[track].value = IntValue(engine)
        },
        setTrackEngineSpace = { track, engine ->
            trackSpaceIds[track].value = IntValue(engine)
        },
        toggleTrackMute = { track ->
            val currentMuted = _trackMutedFlow.value[track]
            val newMuted = !currentMuted
            _trackMutedFlow.value = _trackMutedFlow.value.toMutableList().also { it[track] = newMuted }
            synthController.setPluginControl(muteSymbols[track].controlId, FloatValue(if (newMuted) 1f else 0f))
            log.debug { "Track $track ${if (newMuted) "muted" else "unmuted"}" }
        },
    )

    // ═══════════════════════════════════════════════════════════
    // State flow
    // ═══════════════════════════════════════════════════════════
    private val controlIntents = merge(
        playingId.map { PulsarIntent.Playing(it.asInt() != 0) },
        mutedId.map { PulsarIntent.GlobalPaused(it.asInt() != 0) },
        vibeFlow.map { PulsarIntent.VibeChange(it) },
        energyId.map { PulsarIntent.Energy(it.asFloat()) },
        complexityId.map { PulsarIntent.Complexity(it.asFloat()) },
        spaceId.map { PulsarIntent.Space(it.asFloat()) },
        moodId.map { PulsarIntent.Mood(it.asFloat()) },
        bpmId.map { PulsarIntent.Bpm(it.asFloat()) },
        deepId.map { PulsarIntent.Deep(it.asFloat()) },
        rootNoteId.map { PulsarIntent.RootNote(it.asInt()) },
        scaleId.map { PulsarIntent.Scale(it.asInt()) },
        mixId.map { PulsarIntent.Mix(it.asFloat()) },
        percMixId.map { PulsarIntent.PercMix(it.asFloat()) },
        envelopeModeId.map { PulsarIntent.EnvelopeMode(it.asInt()) },
        selectedTrackFlow.map { PulsarIntent.SelectedTrack(it) },
        _trackMutedFlow.map { PulsarIntent.TrackMuteList(it) },
        *trackEdmIds.mapIndexed { i, flow ->
            flow.map { PulsarIntent.TrackEngineEdm(i, it.asInt()) }
        }.toTypedArray(),
        *trackSpaceIds.mapIndexed { i, flow ->
            flow.map { PulsarIntent.TrackEngineSpace(i, it.asInt()) }
        }.toTypedArray(),
    )

    override val stateFlow: StateFlow<PulsarUiState> =
        controlIntents
            .scan(PulsarUiState(
                bpm = globalTempo.getBpm().toFloat(),
                vibe = vibeFlow.value,
            )) { state, intent ->
                when (intent) {
                    is PulsarIntent.Playing -> state.copy(playing = intent.value)
                    is PulsarIntent.GlobalPaused -> state.copy(globalPaused = intent.value)
                    is PulsarIntent.VibeChange -> state.copy(vibe = intent.value, vibeName = intent.value.name)
                    is PulsarIntent.Energy -> state.copy(energy = intent.value)
                    is PulsarIntent.Complexity -> state.copy(complexity = intent.value)
                    is PulsarIntent.Space -> state.copy(space = intent.value)
                    is PulsarIntent.Mood -> state.copy(mood = intent.value)
                    is PulsarIntent.Bpm -> state.copy(bpm = intent.value)
                    is PulsarIntent.Deep -> state.copy(deep = intent.value)
                    is PulsarIntent.RootNote -> state.copy(rootNote = intent.value)
                    is PulsarIntent.Scale -> state.copy(scaleIndex = intent.value)
                    is PulsarIntent.Mix -> state.copy(mix = intent.value)
                    is PulsarIntent.PercMix -> state.copy(percMix = intent.value)
                    is PulsarIntent.EnvelopeMode -> state.copy(envelopeMode = intent.value)
                    is PulsarIntent.SelectedTrack -> state.copy(selectedTrack = intent.value)
                    is PulsarIntent.TrackEngineEdm -> state.copy(
                        trackEnginesEdm = state.trackEnginesEdm.toMutableList().also {
                            it[intent.track] = intent.engine
                        }
                    )
                    is PulsarIntent.TrackEngineSpace -> state.copy(
                        trackEnginesSpace = state.trackEnginesSpace.toMutableList().also {
                            it[intent.track] = intent.engine
                        }
                    )
                    is PulsarIntent.TrackMuteList -> state.copy(trackMuted = intent.muteList)
                }
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = sharingStrategy,
                initialValue = PulsarUiState(
                    bpm = globalTempo.getBpm().toFloat(),
                    vibe = vibeFlow.value
                )
            )

    // Sync GlobalTempo -> BPM port (must be after stateFlow so Eagerly collection is active)
    init {
        scope.launch(dispatcherProvider.io) {
            globalTempo.bpm.collect { bpm ->
                bpmId.value = FloatValue(bpm.toFloat())
            }
        }
        // Per-section BPM: on each section transition, scale the live BPM by
        // (newMult / oldMult). This composes with user-dialed tempo edits —
        // a half-time breakdown stays half-time even if the user dialed BPM
        // during the previous section. lastSectionMult resets to 1.0 on each
        // vibe load (see applyVibe), so the first section's mult applies on
        // top of vibe.bpm.
        scope.launch(dispatcherProvider.io) {
            arrangementStateFlow
                .map { it.sectionIndex }
                .filter { it >= 0 }
                .distinctUntilChanged()
                .collect { sectionIndex ->
                    val vibe = vibeFlow.value
                    val newMult = vibe.arrangement?.sections
                        ?.getOrNull(sectionIndex)?.bpmMultiplier ?: 1.0f
                    val oldMult = lastSectionMult
                    lastSectionMult = newMult
                    if (newMult == oldMult) return@collect
                    val currentBpm = globalTempo.getBpm().toFloat()
                    val effective = currentBpm * (newMult / oldMult)
                    if (kotlin.math.abs(effective - currentBpm) > 0.01f) {
                        globalTempo.setBpm(effective.toDouble())
                    }
                }
        }
        // Per-section per-track overrides: on each section change, apply the
        // section's TrackSectionOverride to each track (or restore the vibe's
        // base value when no override is present). Static atomics flip
        // immediately; sends update via pushEffectiveSends.
        scope.launch(dispatcherProvider.io) {
            arrangementStateFlow
                .map { it.sectionIndex }
                .filter { it >= 0 }
                .distinctUntilChanged()
                .collect { sectionIndex ->
                    applyTrackOverridesForSection(sectionIndex)
                }
        }
        // Debounced save: waits for restore to complete first to avoid saving stale defaults.
        // Timeout ensures saving isn't blocked forever if presetFlow never emits.
        scope.launch(dispatcherProvider.io) {
            withTimeoutOrNull(5_000L) { restoreComplete.await() }
            stateFlow.drop(1).debounce(2_000L).collect { state ->
                saveState(state)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════
    private suspend fun restoreSavedState() {
        val json = appPreferencesRepository.load().lastPulsarJson ?: return
        val saved = try {
            persistJson.decodeFromString<PulsarUiState>(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to restore Pulsar state" }
            return
        }
        // Push saved values into control flows (drives both UI and C++ engine)
        // Mix is intentionally excluded — always starts at 0 (off)
        // Always resolve vibe from current definitions so structural updates
        // (arrangement, tracks) take effect. Try vibeName first (new format),
        // fall back to saved vibe name (old format), then first vibe.
        val name = saved.vibeName.ifEmpty { saved.vibe.name }
        val currentVibe = vibeList.firstOrNull { it.name == name } ?: vibeList.first()
        applyVibe(currentVibe)
        // Then override with saved macro values
        energyId.value = FloatValue(saved.energy)
        complexityId.value = FloatValue(saved.complexity)
        spaceId.value = FloatValue(saved.space)
        moodId.value = FloatValue(saved.mood)
        bpmId.value = FloatValue(saved.bpm)
        globalTempo.setBpm(saved.bpm.toDouble())
        deepId.value = FloatValue(saved.deep)
        pushEffectiveSends(saved.deep)
        percMixId.value = FloatValue(saved.percMix)
        envelopeModeId.value = IntValue(saved.envelopeMode)
        log.debug { "Restored Pulsar state: vibe=${saved.vibe.name}, bpm=${saved.bpm}" }
    }

    private suspend fun saveState(state: PulsarUiState) {
        // Strip transient fields; mutes are never persisted.
        // MIX_GATED (Orpheus): mix saves as 0 (user dials it in each session).
        // EXPLICIT (DJ app): mix saves as 1 (always audible, play/pause gates audio).
        val savedMix = if (playbackMode == PulsarPlaybackMode.MIX_GATED) 0f else 1f
        val toSave = state.copy(playing = false, selectedTrack = null, mix = savedMix, trackMuted = List(8) { false })
        val json = persistJson.encodeToString(PulsarUiState.serializer(), toSave)
        appPreferencesRepository.update { it.copy(lastPulsarJson = json) }
    }

    // ═══════════════════════════════════════════════════════════
    // Vibe application
    // ═══════════════════════════════════════════════════════════

    private fun compingStyleToInt(style: CompingStyle?): Int =
        style?.engineId ?: 0  // PAD (default for non-CHORDAL tracks — harmless)

    /**
     * Push the entire vibe recipe to C++. Called from setVibe action and restoreSavedState.
     */
    override fun applyVibe(vibe: Vibe) {
        // Set vibeFlow first so pushEffectiveSends reads the new vibe's per-track sends
        vibeFlow.value = vibe
        // globalTempo will be set to vibe.bpm below — that is the 1.0× baseline
        // for the section-BPM collector to compose multipliers against.
        lastSectionMult = 1.0f

        // Reset all track mutes on vibe load
        _trackMutedFlow.value = List(8) { false }
        for (i in 0 until 8) {
            synthController.setPluginControl(muteSymbols[i].controlId, FloatValue(0f))
        }

        // Push per-track voice params.
        // Engine character knobs (volume, harmonics, timbre, morph, mod LFO, holds,
        // sends, range, reverbBrightness, delayFeedback, glide, LPG) live on
        // OrpheusEngine. Each per-engine knob has _edm + _space ports — the
        // C++ render path picks between them per render block based on which
        // slot is active (engine_index == active_edm vs Space).
        vibe.tracks.forEachIndexed { i, tv ->
            val edm = tv.engineEdm
            val spa = tv.engineSpace
            trackEdmIds[i].value = IntValue(edm.engineId.id)
            trackSpaceIds[i].value = IntValue(spa.engineId.id)
            trackVolumeIds[i].value = FloatValue(edm.volume)
            trackVolumeSpaceIds[i].value = FloatValue(spa.volume)
            trackPanIds[i].value = FloatValue(tv.pan)
            trackHarmonicsIds[i].value = FloatValue(edm.harmonics)
            trackHarmonicsSpaceIds[i].value = FloatValue(spa.harmonics)
            trackTimbreIds[i].value = FloatValue(edm.timbre)
            trackTimbreSpaceIds[i].value = FloatValue(spa.timbre)
            trackMorphIds[i].value = FloatValue(edm.morph)
            trackMorphSpaceIds[i].value = FloatValue(spa.morph)
            trackEnvelopeIds[i].value = IntValue(tv.envelopeProfile.id)
            trackRoleIds[i].value = IntValue(tv.role.engineId)
            trackBarStrategyIds[i].value = IntValue(tv.barStrategy.id)
            trackEvoRhythmicIds[i].value = IntValue(if (tv.evolution.rhythmic != null) 1 else 0)
            trackEvoTensionRespIds[i].value = FloatValue(tv.evolution.rhythmic?.tensionResponse ?: 1.0f)
            trackEvoNoteFollowIds[i].value = IntValue(tv.evolution.rhythmic?.noteFollow?.ordinal ?: 0)
            trackEvoPitchModeIds[i].value = IntValue(when (tv.evolution.pitch) {
                null -> 0
                is PitchEvolution.Contour -> 1
                is PitchEvolution.Voicing -> 2
            })
            trackEvoVoicingTensionIds[i].value = FloatValue(
                (tv.evolution.pitch as? PitchEvolution.Voicing)?.tensionResponse ?: 1.0f
            )
            pushMacroMap(i, tv.macroMap)
            trackModLfoRateIds[i].value = FloatValue(edm.modLfoRate)
            trackModLfoRateSpaceIds[i].value = FloatValue(spa.modLfoRate)
            trackModLfoDepthIds[i].value = FloatValue(edm.modLfoDepth)
            trackModLfoDepthSpaceIds[i].value = FloatValue(spa.modLfoDepth)
            trackModLfoShapeIds[i].value = FloatValue(edm.modLfoShape)
            trackModLfoShapeSpaceIds[i].value = FloatValue(spa.modLfoShape)
            trackModLfoCouplingIds[i].value = FloatValue(edm.modLfoCoupling)
            trackModLfoCouplingSpaceIds[i].value = FloatValue(spa.modLfoCoupling)
            trackHoldProbabilityIds[i].value = FloatValue(edm.holdProbability)
            trackHoldProbabilitySpaceIds[i].value = FloatValue(spa.holdProbability)
            trackHoldLengthMinIds[i].value = IntValue(edm.holdLengthMin)
            trackHoldLengthMinSpaceIds[i].value = IntValue(spa.holdLengthMin)
            trackHoldLengthMaxIds[i].value = IntValue(edm.holdLengthMax)
            trackHoldLengthMaxSpaceIds[i].value = IntValue(spa.holdLengthMax)
            trackNoteRangeLowIds[i].value = IntValue(edm.noteRangeLow)
            trackNoteRangeLowSpaceIds[i].value = IntValue(spa.noteRangeLow)
            trackNoteRangeHighIds[i].value = IntValue(edm.noteRangeHigh)
            trackNoteRangeHighSpaceIds[i].value = IntValue(spa.noteRangeHigh)
            trackReverbBrightnessIds[i].value = FloatValue(edm.reverbBrightness)
            trackReverbBrightnessSpaceIds[i].value = FloatValue(spa.reverbBrightness)
            genreDensityIds[i].value = FloatValue(tv.density)
            trackDelayFeedbackIds[i].value = FloatValue(edm.delayFeedback ?: -1f)
            trackDelayFeedbackSpaceIds[i].value = FloatValue(spa.delayFeedback ?: -1f)
            trackGlideRateIds[i].value = FloatValue(edm.glideRate)
            trackGlideRateSpaceIds[i].value = FloatValue(spa.glideRate)
            trackLickModeIds[i].value = IntValue(when (tv.lickMode) {
                is LickMode.None -> 0
                is LickMode.Squash -> 1
                is LickMode.Fill -> 2
            })
            trackCompingStyleIds[i].value = IntValue(compingStyleToInt(tv.chordComping?.style))
            trackArpModeIds[i].value = IntValue(tv.chordComping?.arpMode?.ordinal ?: 0)
            trackArpSpeedIds[i].value = FloatValue(tv.chordComping?.arpSpeed ?: 0.2f)
            trackArpDirectionIds[i].value = IntValue(tv.chordComping?.arpDirection?.ordinal ?: 0)
            trackInversionIds[i].value = IntValue(tv.chordComping?.sectionInversion?.ordinal ?: 0)
            trackHumanDropIds[i].value = FloatValue(tv.chordComping?.humanization?.dropProbability ?: 0f)
            trackHumanGhostIds[i].value = FloatValue(tv.chordComping?.humanization?.ghostProbability ?: 0f)
            trackHumanOctaveIds[i].value = FloatValue(tv.chordComping?.humanization?.octaveJumpProbability ?: 0f)
            trackHumanExtIds[i].value = FloatValue(tv.chordComping?.humanization?.extensionProbability ?: 0f)
            trackFillEveryNIds[i].value = IntValue(tv.chordComping?.fills?.everyNBars ?: 0)
            trackFillTypeIds[i].value = IntValue(tv.chordComping?.fills?.fillType?.ordinal ?: 0)
            trackFillSkipProbIds[i].value = FloatValue(tv.chordComping?.fills?.skipProbability ?: 0f)
            trackChordFollowIds[i].value = IntValue(tv.chordFollow.ordinal)
            // LPG mode is per-engine: edm.lpgMode applies to the EDM slot,
            // engineSpace.lpgMode to the SPACE slot. C++ selects per render block
            // based on which engine is active (orpheus_unit_pulsar.cpp:2451).
            trackLpgModeIds[i].value = IntValue(edm.lpgMode.id)
            trackLpgModeSpaceIds[i].value = IntValue(spa.lpgMode.id)
            trackLpgDecayIds[i].value = FloatValue(edm.lpgDecay)
            trackLpgDecaySpaceIds[i].value = FloatValue(spa.lpgDecay)
            trackLpgColourIds[i].value = FloatValue(edm.lpgColour)
            trackLpgColourSpaceIds[i].value = FloatValue(spa.lpgColour)
            trackDelaySendSpaceIds[i].value = FloatValue(spa.delaySend)
            trackReverbSendSpaceIds[i].value = FloatValue(spa.reverbSend)
        }
        // Seed per-track send bases from vibe; section overrides may swap these later.
        // sendBase[] tracks the EDM-side default; the C++ delay unit reads the
        // active slot's send (via pulsar_track_active_engine) for feedback blending.
        for (i in 0 until 8) {
            val tv = vibe.tracks.getOrNull(i)
            sendBaseDelay[i] = tv?.engineEdm?.delaySend ?: 0f
            sendBaseReverb[i] = tv?.engineEdm?.reverbSend ?: 0f
        }
        stepCountId.value = IntValue(vibe.stepCount)
        pushEffectiveSends(deepId.value.asFloat())

        // Push vibe-defined effect params to dedicated Pulsar delay/reverb
        val fx = vibe.effects
        pulsarDelayTimeAId.value = FloatValue(fx.delayTimeA)
        pulsarDelayTimeBId.value = FloatValue(fx.delayTimeB)
        pulsarDelayFeedbackId.value = FloatValue(fx.delayFeedback)
        pulsarDelayDampingId.value = FloatValue(fx.delayDamping)
        pulsarReverbSizeId.value = FloatValue(fx.reverbSize)
        pulsarReverbDampingId.value = FloatValue(fx.reverbDamping)
        pulsarReverbBrightnessId.value = FloatValue(fx.reverbBrightness)

        // Push genre profile
        genreSwingId.value = FloatValue(vibe.genre.swingAmount)
        genreGhostProbId.value = FloatValue(vibe.genre.ghostProbability)
        genreNoteRangeLowId.value = IntValue(vibe.genre.noteRangeLow)
        genreNoteRangeHighId.value = IntValue(vibe.genre.noteRangeHigh)
        genreRhythmDensityId.value = FloatValue(vibe.genre.rhythmDensity)
        genreProgressionStyleId.value = IntValue(vibe.genre.progressionStyle.ordinal)
        genreChordsPerBarId.value = IntValue(vibe.genre.chordsPerBar)
        progressionAnchorId.value = IntValue(vibe.progressionAnchor.barsBetweenResets)
        progressionDriftRangeId.value = FloatValue(vibe.progressionDriftRange)

        // Custom chord transition matrix (49 floats = 7x7 row-major)
        val chordMatrix = vibe.genre.chordTransitionMatrix
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "chord_matrix_active"),
            IntValue(if (chordMatrix != null) 1 else 0)
        )
        if (chordMatrix != null) {
            for (i in 0 until minOf(chordMatrix.size, 49)) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "chord_matrix_$i"),
                    FloatValue(chordMatrix[i])
                )
            }
        }

        // Custom progression (optional chord sequence override, max 8 slots).
        // Write degrees + glides first, then length (acts as a release fence on
        // the C++ side). Per-chord glide is applied on transition into each chord.
        val customProg = vibe.genre.customProgression
        if (customProg != null) {
            customProg.forEachIndexed { i, step ->
                if (i < 8) {
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "custom_progression_$i"),
                        IntValue(step.degree)
                    )
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "custom_progression_glide_$i"),
                        FloatValue(step.glideRate)
                    )
                }
            }
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "custom_progression_length"),
                IntValue(minOf(customProg.size, 8))
            )
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "custom_progression_active"),
                IntValue(1)
            )
        } else {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "custom_progression_active"),
                IntValue(0)
            )
        }

        // Push lick (data first, length last as release fence)
        val lick = vibe.lick
        if (lick != null) {
            lick.steps.forEachIndexed { i, step ->
                lickDataIds[i * 4].value = FloatValue(step.scaleDegree.toFloat())
                lickDataIds[i * 4 + 1].value = FloatValue(step.duration)
                lickDataIds[i * 4 + 2].value = FloatValue(step.velocity)
                // -1 = "use the track's TrackVoice.glideRate"; explicit value overrides.
                lickDataIds[i * 4 + 3].value = FloatValue(step.glideRate)
            }
            lickMutationId.value = FloatValue(vibe.lickMutation)
            lickOctaveId.value = IntValue(vibe.lickOctave)
            lickLoopLengthId.value = IntValue(lick.loopLength)
            lickLengthId.value = IntValue(lick.steps.size)
        } else {
            lickLoopLengthId.value = IntValue(0)
            lickLengthId.value = IntValue(0)
        }

        seedId.value = IntValue(vibe.seed)
        envelopeModeId.value = IntValue(vibe.envelopeType.modeIndex)

        // Push tension profile
        val t = vibe.tension
        tensionInnerBarsId.value = IntValue(t.innerBars)
        tensionOuterBarsId.value = IntValue(t.outerBars)
        tensionOuterDepthId.value = FloatValue(t.outerDepth)
        tensionVolumeId.value = FloatValue(t.volume)
        tensionTimingId.value = FloatValue(t.timing)
        tensionOctaveShiftId.value = IntValue(if (t.tonal.octaveShift) 1 else 0)
        tensionKeyShiftId.value = IntValue(t.tonal.keyShift)
        tensionHalfLickId.value = IntValue(if (t.tonal.halfLick) 1 else 0)
        tensionChromaticPassingId.value = FloatValue(t.tonal.chromaticPassing)
        val e = t.evolution
        tensionEvoTimbreLowId.value = FloatValue(e.timbreLow)
        tensionEvoTimbreHighId.value = FloatValue(e.timbreHigh)
        tensionEvoTimbreProbId.value = FloatValue(e.timbreProbability)
        tensionEvoMorphLowId.value = FloatValue(e.morphLow)
        tensionEvoMorphHighId.value = FloatValue(e.morphHigh)
        tensionEvoMorphProbId.value = FloatValue(e.morphProbability)
        tensionEvoHarmLowId.value = FloatValue(e.harmonicsLow)
        tensionEvoHarmHighId.value = FloatValue(e.harmonicsHigh)
        tensionEvoHarmProbId.value = FloatValue(e.harmonicsProbability)
        tensionEvoAttackPointId.value = FloatValue(e.attackPoint)
        tensionEvoReleaseSpeedId.value = FloatValue(e.releaseSpeed)
        tensionSpurtChanceId.value = FloatValue(t.spurtChance)
        // Per-track evolution weight
        vibe.tracks.forEachIndexed { i, tv ->
            trackEvoWeightIds[i].value = FloatValue(tv.evolutionWeight)
        }

        // Set macro defaults
        rootNoteId.value = IntValue(vibe.rootNote.noteIndex)
        scaleId.value = IntValue(vibe.scaleType.scaleIndex)
        bpmId.value = FloatValue(vibe.bpm)
        globalTempo.setBpm(vibe.bpm.toDouble())
        energyId.value = FloatValue(vibe.energy)
        complexityId.value = FloatValue(vibe.complexity)
        spaceId.value = FloatValue(vibe.space)
        moodId.value = FloatValue(vibe.mood)
        deepId.value = FloatValue(vibe.deep)

        // Push arrangement data (MUST be before vibe generation increment)
        pushArrangement(vibe)

        // In MIX_GATED mode (Orpheus), auto-start on vibe load — mix knob controls audibility.
        // In EXPLICIT mode (DJ app), playing is controlled by PulsarPlaybackBridge, mix stays at 1.
        if (playbackMode == PulsarPlaybackMode.MIX_GATED) {
            playingId.value = IntValue(1)
            mixId.value = FloatValue(0f)
        } else {
            mixId.value = FloatValue(1f)
        }

        // Trigger vibe reload (MUST be last)
        vibeGenerationId.value = IntValue(vibeGenerationId.value.asInt() + 1)
    }

    /**
     * Push the 16 macro map floats for a single track.
     */
    private fun pushMacroMap(trackIndex: Int, map: TrackMacroMap) {
        val ids = trackMacroIds[trackIndex]
        ids[0].value = FloatValue(map.energyVolume.min)
        ids[1].value = FloatValue(map.energyVolume.max)
        ids[2].value = FloatValue(map.energyDensity.min)
        ids[3].value = FloatValue(map.energyDensity.max)
        ids[4].value = FloatValue(map.complexitySwing.min)
        ids[5].value = FloatValue(map.complexitySwing.max)
        ids[6].value = FloatValue(map.complexityVariation.min)
        ids[7].value = FloatValue(map.complexityVariation.max)
        ids[8].value = FloatValue(map.spaceDecay.min)
        ids[9].value = FloatValue(map.spaceDecay.max)
        ids[10].value = FloatValue(map.moodHarmonics.min)
        ids[11].value = FloatValue(map.moodHarmonics.max)
        ids[12].value = FloatValue(map.moodTimbre.min)
        ids[13].value = FloatValue(map.moodTimbre.max)
    }

    /**
     * Apply per-track overrides defined on the given section. For each track,
     * the effective value is `override?.field ?: tv.field`. Restores base
     * values automatically when the section has no override for that track.
     */
    private fun applyTrackOverridesForSection(sectionIndex: Int) {
        val vibe = vibeFlow.value
        val section = vibe.arrangement?.sections?.getOrNull(sectionIndex) ?: return
        val overrides = section.trackOverrides
        for (i in 0 until 8) {
            val tv = vibe.tracks.getOrNull(i) ?: continue
            val edm = tv.engineEdm
            val o = overrides?.get(i)
            trackHoldProbabilityIds[i].value = FloatValue(o?.holdProbability ?: edm.holdProbability)
            trackHoldLengthMinIds[i].value = IntValue(o?.holdLengthMin ?: edm.holdLengthMin)
            trackHoldLengthMaxIds[i].value = IntValue(o?.holdLengthMax ?: edm.holdLengthMax)
            trackVolumeIds[i].value = FloatValue(o?.volume ?: edm.volume)
            genreDensityIds[i].value = FloatValue(o?.density ?: tv.density)
            trackEnvelopeIds[i].value = IntValue((o?.envelopeProfile ?: tv.envelopeProfile).id)
            sendBaseDelay[i] = o?.delaySend ?: edm.delaySend
            sendBaseReverb[i] = o?.reverbSend ?: edm.reverbSend
        }
        pushEffectiveSends(deepId.value.asFloat())
    }

    private fun pushEffectiveSends(deep: Float) {
        val vibe = vibeFlow.value
        val space = spaceId.value.asFloat()
        val floor = vibe.effects.deepFloor
        // SPACE boosts DEEP with a per-vibe floor: effectiveDeep = deep * (floor + space * (1 - floor))
        val effectiveDeep = deep * (floor + space * (1.0f - floor))
        for (i in 0 until 8) {
            trackDelaySendIds[i].value = FloatValue(sendBaseDelay[i] * effectiveDeep)
            trackReverbSendIds[i].value = FloatValue(sendBaseReverb[i] * effectiveDeep)
        }
    }

    /**
     * Pack and write arrangement data to engine atomics via SynthController.
     *
     * Layout for C++ engine arrays (must match orpheus_engine_routing.cpp and load_vibe()):
     *
     * section_data[s * 21 + field]:
     *   0=bars_min, 1=bars_max, 2=bar_step (1 = any; 2 = odd/even only; etc.),
     *   3=recency_decay,
     *   4=macro_energy, 5=macro_complexity, 6=macro_space, 7=macro_mood,
     *   8=has_solo/solo_mode_id, 9..17=solo params (format depends on new vs legacy),
     *   12=bars_per_soloist_max, 13=solo_transition_bars, 14=improv_carryover,
     *   15=transition_count, 16=reserved, 17=reserved,
     *   18=comping_style_override (-1=no override), 19=comping_inversion_override, 20=chord_follow_override
     *
     * section_transitions[s * 8 * 3 + t * 3 + field]:
     *   0=targetIndex, 1=weight, 2=transitionBars (up to 8 transitions per section)
     *
     * track_solo_behavior[t * 15 + field]:
     *   0=volume_boost, 1=density_boost, 2=timbre_min, 3=timbre_max,
     *   4=morph_min, 5=morph_max, 6=harmonics_min, 7=harmonics_max,
     *   8=evolution_intensity, 9=fill_probability,
     *   10=rest_probability, 11=hold_probability, 12=density_curve_min,
     *   13=density_curve_max, 14=chromatic_passing
     *
     * track_ducking[t * 6 + field]:
     *   0=volume_reduction, 1=density_reduction, 2=ghost_reduction,
     *   3=fill_suppression, 4=simplify (0/1), 5=reverb_boost
     *
     * track_solo_markov[t * 15 + i] = intervalWeights[i]
     *
     * arrangement_generation written last as acquire fence.
     */
    private fun pushArrangement(vibe: Vibe) {
        val arr = vibe.arrangement
        if (arr == null) {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "arrangement_active"), IntValue(0)
            )
            log.debug { "Arrangement: inactive (vibe=${vibe.name})" }
            return
        }
        log.debug { "Arrangement: pushing ${arr.sections.size} sections for vibe=${vibe.name}" }

        // Scalar arrangement headers
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_active"), IntValue(1)
        )
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_section_count"), IntValue(arr.sections.size)
        )
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_intro_index"), IntValue(arr.introIndex ?: -1)
        )
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_outro_index"), IntValue(arr.outroIndex ?: -1)
        )

        // Section data (21 floats per section)
        arr.sections.forEachIndexed { s, section ->
            val base = s * 21
            val mo = section.macroOverrides
            fun setSection(field: Int, v: Float) =
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_data_${base + field}"), FloatValue(v)
                )
            // Must match C++ load_vibe() unpack order exactly:
            // [0]=bars_min, [1]=bars_max, [2]=bar_step (1 = any value in
            //   [bars_min, bars_max]; 2 = odd-or-even-only stride), [3]=recency_decay,
            // [4]=transition_count, [5]=energy, [6]=complexity, [7]=space, [8]=mood,
            // [9..17]=solo data (format depends on new vs legacy system)
            setSection(0, section.barsMin.toFloat())
            setSection(1, section.barsMax.toFloat())
            setSection(2, section.barStep.toFloat())
            setSection(3, section.recencyDecay)
            setSection(4, section.transitions.size.toFloat())
            setSection(5, mo?.energy ?: -1f)
            setSection(6, mo?.complexity ?: -1f)
            setSection(7, mo?.space ?: -1f)
            setSection(8, mo?.mood ?: -1f)

            val sectionSolo = section.soloMode
            if (sectionSolo != null) {
                val soloModeId = when (sectionSolo) {
                    is SoloMode.LongFill -> 1f
                    is SoloMode.LickBuilder -> 2f
                    is SoloMode.Jam -> 3f
                }
                val soloProbability = when (sectionSolo) {
                    is SoloMode.LongFill -> sectionSolo.probability
                    is SoloMode.LickBuilder -> sectionSolo.probability
                    is SoloMode.Jam -> sectionSolo.probability
                }
                setSection(9, soloModeId)
                setSection(10, soloProbability)
                setSection(11, (sectionSolo as? SoloMode.LickBuilder)?.mutationRate ?: 0.5f)
                setSection(12, (sectionSolo as? SoloMode.Jam)?.lickInfluence ?: 0.5f)
                setSection(13, (sectionSolo as? SoloMode.LongFill)?.barsMin?.toFloat() ?: 2f)
                setSection(14, (sectionSolo as? SoloMode.LongFill)?.barsMax?.toFloat() ?: 4f)
                setSection(15, 0f) // reserved
                setSection(16, 0f) // reserved
                setSection(17, 0f) // reserved
            } else {
                // No solo in this section
                setSection(9, 0f)
                for (slot in 10..17) setSection(slot, 0f)
            }

            // Section-level comping overrides (slots 18-20); -1.0 = no override
            setSection(18, compingStyleOrSentinel(section.compingStyle))
            setSection(19, compingInversionOrSentinel(section.compingInversion))
            setSection(20, chordFollowOrSentinel(section.chordFollow))

            // Per-track section overrides. Always write all 8 slots so a vibe
            // reload doesn't carry stale overrides from a previous vibe.
            // -1 = no override (track falls back to its base value or to the
            // section-level override if present).
            for (t in 0 until 8) {
                val o = section.trackOverrides?.get(t)
                val baseIdx = s * 8 + t
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_comping_$baseIdx"),
                    IntValue(o?.compingStyle?.engineId ?: -1)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_inversion_$baseIdx"),
                    IntValue(o?.sectionInversion?.ordinal ?: -1)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_arp_mode_$baseIdx"),
                    IntValue(o?.arpMode?.ordinal ?: -1)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_track_chord_follow_$baseIdx"),
                    IntValue(o?.chordFollow?.ordinal ?: -1)
                )
            }

            // Transitions for this section (up to 8 edges × 3 floats per edge:
            // [target_index, weight, transition_bars]).
            val transBase = s * 8 * 3
            section.transitions.forEachIndexed { t, tr ->
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 3}"),
                    FloatValue(tr.targetIndex.toFloat())
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 3 + 1}"),
                    FloatValue(tr.weight)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 3 + 2}"),
                    FloatValue(tr.transitionBars.toFloat())
                )
            }

            // --- Per-section progression override ---
            // Per-chord glide is applied on transition into each chord by the
            // pulsar progression runner; 0 = no glide.
            val cp = section.customProgression
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_progression_active_$s"),
                IntValue(cp?.size ?: 0)
            )
            if (cp != null) {
                for (i in cp.indices) {
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_progression_degree_${s * 8 + i}"),
                        IntValue(cp[i].degree)
                    )
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_progression_glide_${s * 8 + i}"),
                        FloatValue(cp[i].glideRate)
                    )
                }
            }
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_chords_per_bar_$s"),
                IntValue(section.chordsPerBar ?: 0)
            )

            // --- Per-section tension override ---
            val to = section.tensionOverride
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_tension_active_$s"),
                IntValue(if (to != null) 1 else 0)
            )
            if (to != null) {
                val tBase = s * 21
                fun setTension(field: Int, v: Float) =
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_tension_data_${tBase + field}"),
                        FloatValue(v)
                    )
                setTension(0,  to.innerBars.toFloat())
                setTension(1,  to.outerBars.toFloat())
                setTension(2,  to.outerDepth)
                setTension(3,  to.volume)
                setTension(4,  to.timing)
                setTension(5,  if (to.tonal.octaveShift) 1f else 0f)
                setTension(6,  to.tonal.keyShift.toFloat())
                setTension(7,  if (to.tonal.halfLick) 1f else 0f)
                setTension(8,  to.tonal.chromaticPassing)
                setTension(9,  to.evolution.timbreLow)
                setTension(10, to.evolution.timbreHigh)
                setTension(11, to.evolution.timbreProbability)
                setTension(12, to.evolution.morphLow)
                setTension(13, to.evolution.morphHigh)
                setTension(14, to.evolution.morphProbability)
                setTension(15, to.evolution.harmonicsLow)
                setTension(16, to.evolution.harmonicsHigh)
                setTension(17, to.evolution.harmonicsProbability)
                setTension(18, to.evolution.attackPoint)
                setTension(19, to.evolution.releaseSpeed)
                setTension(20, to.spurtChance)
            }

            // --- Per-section comping humanization override ---
            val ch = section.compingHumanization
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "section_comping_humanization_active_$s"),
                IntValue(if (ch != null) 1 else 0)
            )
            if (ch != null) {
                val chBase = s * 4
                fun setCh(field: Int, v: Float) =
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "section_comping_humanization_data_${chBase + field}"),
                        FloatValue(v)
                    )
                setCh(0, ch.dropProbability)
                setCh(1, ch.ghostProbability)
                setCh(2, ch.octaveJumpProbability)
                setCh(3, ch.extensionProbability)
            }
        }

        // Band solo config
        val bandConfig = vibe.band
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "band_active"),
            IntValue(if (bandConfig != null) 1 else 0)
        )
        if (bandConfig != null) {
            synthController.setPluginControl(
                PluginControlId(PULSAR_URI, "band_member_count"),
                IntValue(bandConfig.members.size)
            )
            bandConfig.members.forEachIndexed { m, member ->
                val base = m * 12
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 0}"),
                    FloatValue(member.tracks.size.toFloat())
                )
                member.tracks.forEachIndexed { t, trackIdx ->
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "band_member_data_${base + 1 + t}"),
                        FloatValue(trackIdx.toFloat())
                    )
                }
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 9}"),
                    FloatValue(if (member.alwaysActive) 1f else 0f)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 10}"),
                    FloatValue(member.loudness)
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_member_data_${base + 11}"),
                    FloatValue(member.creativity)
                )
            }
            val N = bandConfig.members.size
            for (i in 0 until minOf(bandConfig.handoffMatrix.size, N * N)) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_handoff_$i"),
                    FloatValue(bandConfig.handoffMatrix[i])
                )
            }
            for (i in 0 until minOf(bandConfig.pullInMatrix.size, N * N)) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "band_pull_in_$i"),
                    FloatValue(bandConfig.pullInMatrix[i])
                )
            }
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_pull_in_bars_min"), IntValue(bandConfig.pullInBarsMin))
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_pull_in_bars_max"), IntValue(bandConfig.pullInBarsMax))
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_bars_per_lead_min"), IntValue(bandConfig.barsPerLeadMin))
            synthController.setPluginControl(PluginControlId(PULSAR_URI, "band_bars_per_lead_max"), IntValue(bandConfig.barsPerLeadMax))
        }

        // Per-track solo behavior and ducking (use track's own config if present,
        // else use defaults derived from the track's envelope profile)
        vibe.tracks.forEachIndexed { t, tv ->
            val behavior = tv.soloBehavior ?: defaultSoloBehavior(tv.envelopeProfile)
            val markov = behavior.markovConfig
            val behaviorBase = t * 15
            fun setSolo(field: Int, v: Float) =
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "track_solo_behavior_${behaviorBase + field}"),
                    FloatValue(v)
                )
            setSolo(0, behavior.volumeBoost)
            setSolo(1, behavior.densityBoost)
            setSolo(2, behavior.timbreMin)
            setSolo(3, behavior.timbreMax)
            setSolo(4, behavior.morphMin)
            setSolo(5, behavior.morphMax)
            setSolo(6, behavior.harmonicsMin)
            setSolo(7, behavior.harmonicsMax)
            setSolo(8, behavior.evolutionIntensity)
            setSolo(9, behavior.fillProbability)
            setSolo(10, markov?.restProbability ?: 0.15f)
            setSolo(11, markov?.holdProbability ?: 0.2f)
            setSolo(12, markov?.densityCurveMin ?: 0.4f)
            setSolo(13, markov?.densityCurveMax ?: 0.8f)
            setSolo(14, markov?.chromaticPassing ?: 0.1f)

            // Markov interval weights (15 floats)
            val markovBase = t * 15
            val weights = markov?.intervalWeights
            for (i in 0 until 15) {
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "track_solo_markov_${markovBase + i}"),
                    FloatValue(weights?.getOrNull(i) ?: (1f / 15f))
                )
            }

            // Ducking profile
            val ducking = tv.duckingProfile ?: defaultDuckingProfile(tv.envelopeProfile)
            val duckBase = t * 6
            fun setDuck(field: Int, v: Float) =
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "track_ducking_${duckBase + field}"),
                    FloatValue(v)
                )
            setDuck(0, ducking.volumeReduction)
            setDuck(1, ducking.densityReduction)
            setDuck(2, ducking.ghostReduction)
            setDuck(3, ducking.fillSuppression)
            setDuck(4, if (ducking.simplify) 1f else 0f)
            setDuck(5, ducking.reverbBoost)
        }

        // Write arrangement generation last as release fence (triggers C++ load_vibe re-read)
        synthController.setPluginControl(
            PluginControlId(PULSAR_URI, "arrangement_generation"),
            IntValue(1)
        )
    }

    private fun defaultSoloBehavior(profile: EnvelopeProfile): SoloBehavior = when (profile) {
        EnvelopeProfile.RHYTHM -> SoloBehavior(
            fillProbability = 0.8f, densityBoost = 0.4f,
            markovConfig = SoloMarkovConfig.RHYTHMIC_DEFAULT,
        )
        EnvelopeProfile.MELODIC -> SoloBehavior(
            markovConfig = SoloMarkovConfig.MELODIC_DEFAULT,
        )
        EnvelopeProfile.EFFECT -> SoloBehavior(
            harmonicsMin = 0.1f, harmonicsMax = 0.9f,
            markovConfig = SoloMarkovConfig.EFFECT_DEFAULT,
        )
        EnvelopeProfile.WILD -> SoloBehavior(
            volumeBoost = 0.3f, evolutionIntensity = 1.5f,
            markovConfig = SoloMarkovConfig.WILD_DEFAULT,
        )
        EnvelopeProfile.DRONE -> SoloBehavior(
            markovConfig = SoloMarkovConfig.MELODIC_DEFAULT,
        )
    }

    private fun defaultDuckingProfile(profile: EnvelopeProfile): DuckingProfile = when (profile) {
        EnvelopeProfile.RHYTHM -> DuckingProfile(
            volumeReduction = 0.2f, densityReduction = 0.5f,
            ghostReduction = 0.7f, fillSuppression = 0.9f,
        )
        EnvelopeProfile.MELODIC -> DuckingProfile()
        EnvelopeProfile.EFFECT -> DuckingProfile(
            volumeReduction = 0.4f, densityReduction = 0.6f,
            reverbBoost = 0.15f, simplify = false,
        )
        EnvelopeProfile.WILD -> DuckingProfile(
            volumeReduction = 0.5f, densityReduction = 0.7f,
            fillSuppression = 0.95f,
        )
        EnvelopeProfile.DRONE -> DuckingProfile()
    }

    private fun compingStyleOrSentinel(s: CompingStyle?): Float =
        s?.engineId?.toFloat() ?: -1.0f

    private fun compingInversionOrSentinel(inv: SectionInversion?): Float =
        inv?.ordinal?.toFloat() ?: -1.0f

    private fun chordFollowOrSentinel(cf: ChordFollow?): Float =
        cf?.ordinal?.toFloat() ?: -1.0f

    companion object {
        private val previewVibe = Vibe(
            name = "Preview",
            bpm = 120f,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0f,
                noteRangeLow = 36,
                noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.SPARSE.density,
            ),
            tracks = List(8) {
                val engine = OrpheusEngine(engineId = OrpheusEngineId.VIRTUAL_ANALOG)
                TrackVoice(
                    engineEdm = engine,
                    engineSpace = engine,
                    role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
                )
            },
        )

        fun previewFeature(state: PulsarUiState = PulsarUiState(
            vibe = previewVibe,
        )): PulsarFeature =
            object : PulsarFeature {
                override val vibeList: List<Vibe> = listOf(previewVibe)
                override val vibeFlow: StateFlow<Vibe> = MutableStateFlow(previewVibe)
                override fun applyVibe(vibe: Vibe) {}
                override val arrangementStateFlow: StateFlow<PulsarArrangementState> = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
                override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(state)
                override val actions: PulsarPanelActions = PulsarPanelActions.EMPTY
            }

        @Composable
        fun feature(): PulsarFeature =
            synthFeature<PulsarViewModel, PulsarFeature>()
    }
}
