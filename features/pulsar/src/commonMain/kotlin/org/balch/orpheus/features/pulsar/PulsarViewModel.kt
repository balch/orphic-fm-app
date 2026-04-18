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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMetadata
import org.balch.orpheus.core.media.PlaybackMode
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
    val togglePlaying: () -> Unit = {},
    val toggleGlobalPause: () -> Unit = {},
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
@ClassKey(PulsarViewModel::class)
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
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val mediaSessionManager: MediaSessionManager,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val playbackMode: PulsarPlaybackMode,
) : PulsarFeature {

    override val vibeList: List<Vibe> = vibeProviders.map { it.vibe }.sortedBy { it.name }

    @Volatile
    private var mediaPaused = false

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
    private val lickDataIds = (0..95).map { i ->
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
    private val vibeFlow = MutableStateFlow(vibeList.first())
    private val restoreComplete = CompletableDeferred<Unit>()
    private val persistJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

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
        togglePlaying = {
            val current = playingId.value.asInt()
            playingId.value = IntValue(if (current != 0) 0 else 1)
        },
        toggleGlobalPause = {
            val currentlyMuted = mutedId.value.asInt() != 0
            mutedId.value = IntValue(if (currentlyMuted) 0 else 1)
            if (currentlyMuted) {
                // Unmuting: start Pulsar
                playingId.value = IntValue(1)
            } else {
                // Muting: stop Pulsar to save CPU
                playingId.value = IntValue(0)
            }
        },
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

    private fun buildArrangementSubtitle(
        state: PulsarArrangementState,
        vibe: Vibe,
    ): String {
        if (state.sectionIndex < 0) return "Pulsar"

        val section = vibe.arrangement?.sections?.getOrNull(state.sectionIndex)
        val sectionName = section?.name ?: "Section ${state.sectionIndex + 1}"
        val currentBar = state.barsElapsed + 1

        return buildString {
            append(sectionName)
            if (state.soloActive && state.soloTrack >= 0) {
                val bandMembers = vibe.band?.members
                val soloistName = if (state.bandSolo && bandMembers != null) {
                    bandMembers.getOrElse(state.soloTrack) { null }?.name
                } else {
                    PULSAR_TRACK_NAMES.getOrElse(state.soloTrack) { null }
                }
                if (soloistName != null) {
                    append(" \u00b7 $soloistName Solo")
                }
            }
            append(" \u00b7 Bar $currentBar/${state.barsTotal}")
        }
    }

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
        // Debounced save: waits for restore to complete first to avoid saving stale defaults.
        // Timeout ensures saving isn't blocked forever if presetFlow never emits.
        scope.launch(dispatcherProvider.io) {
            withTimeoutOrNull(5_000L) { restoreComplete.await() }
            stateFlow.drop(1).debounce(2_000L).collect { state ->
                saveState(state)
            }
        }
    }

    init {
        // Activate/deactivate MediaSession based on global mute state.
        // When paused via notification (mediaPaused=true), keep session active
        // so the Play button can re-activate.
        scope.launch(dispatcherProvider.io) {
            mutedId.collect { value ->
                val muted = value.asInt() != 0
                val active = !muted || mediaPaused
                mediaSessionStateManager.setPulsarActive(active)
            }
        }

        // Update notification metadata when arrangement state, vibe, or mute changes
        scope.launch(dispatcherProvider.io) {
            combine(
                arrangementStateFlow,
                vibeFlow,
                mutedId.map { it.asInt() == 0 },
            ) { arrState, vibe, playing ->
                Triple(arrState, vibe, playing)
            }.collect { (arrState, vibe, playing) ->
                if (playing || mediaPaused) {
                    val subtitle = buildArrangementSubtitle(arrState, vibe)
                    mediaSessionManager.updateMetadata(
                        PlaybackMetadata(
                            title = vibe.name,
                            mode = PlaybackMode.PULSAR,
                            isPlaying = playing,
                            subtitle = subtitle,
                        )
                    )
                }
            }
        }

        // Wire skip actions to vibe cycling
        mediaSessionManager.onSkipNext = {
            val currentIndex = vibeList.indexOfFirst { it.name == vibeFlow.value.name }
            val nextIndex = (currentIndex + 1) % vibeList.size
            applyVibe(vibeList[nextIndex])
        }
        mediaSessionManager.onSkipPrevious = {
            val currentIndex = vibeList.indexOfFirst { it.name == vibeFlow.value.name }
            val prevIndex = if (currentIndex <= 0) vibeList.size - 1 else currentIndex - 1
            applyVibe(vibeList[prevIndex])
        }

        // Wire play/pause to global mute.
        // mediaPaused flag keeps the MediaSession alive during notification pause
        // so the Play button remains functional.
        mediaSessionManager.onPlay = {
            mediaPaused = false
            mutedId.value = IntValue(0)
            if (playingId.value.asInt() == 0) {
                playingId.value = IntValue(1)
            }
        }
        mediaSessionManager.onPause = {
            mediaPaused = true
            mutedId.value = IntValue(1)
            playingId.value = IntValue(0)
        }

        // Subscribe to PlaybackLifecycleEvent.StopAll (e.g., timer expiry)
        scope.launch(dispatcherProvider.io) {
            playbackLifecycleManager.events.collect { event ->
                if (event is PlaybackLifecycleEvent.StopAll) {
                    log.debug { "Received StopAll — pausing" }
                    mutedId.value = IntValue(1)
                    playingId.value = IntValue(0)
                }
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
    private fun applyVibe(vibe: Vibe) {
        // Set vibeFlow first so pushEffectiveSends reads the new vibe's per-track sends
        vibeFlow.value = vibe

        // Reset all track mutes on vibe load
        _trackMutedFlow.value = List(8) { false }
        for (i in 0 until 8) {
            synthController.setPluginControl(muteSymbols[i].controlId, FloatValue(0f))
        }

        // Push per-track voice params
        vibe.tracks.forEachIndexed { i, tv ->
            trackEdmIds[i].value = IntValue(tv.engineEdm.id)
            trackSpaceIds[i].value = IntValue(tv.engineSpace.id)
            trackVolumeIds[i].value = FloatValue(tv.volume)
            trackPanIds[i].value = FloatValue(tv.pan)
            trackHarmonicsIds[i].value = FloatValue(tv.harmonics)
            trackTimbreIds[i].value = FloatValue(tv.timbre)
            trackMorphIds[i].value = FloatValue(tv.morph)
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
            trackModLfoRateIds[i].value = FloatValue(tv.modLfoRate)
            trackModLfoDepthIds[i].value = FloatValue(tv.modLfoDepth)
            trackModLfoShapeIds[i].value = FloatValue(tv.modLfoShape)
            trackModLfoCouplingIds[i].value = FloatValue(tv.modLfoCoupling)
            trackHoldProbabilityIds[i].value = FloatValue(tv.holdProbability)
            trackHoldLengthMinIds[i].value = IntValue(tv.holdLengthMin)
            trackHoldLengthMaxIds[i].value = IntValue(tv.holdLengthMax)
            trackNoteRangeLowIds[i].value = IntValue(tv.noteRangeLow ?: 0)
            trackNoteRangeHighIds[i].value = IntValue(tv.noteRangeHigh ?: 0)
            trackReverbBrightnessIds[i].value = FloatValue(tv.reverbBrightness)
            genreDensityIds[i].value = FloatValue(tv.density)
            trackDelayFeedbackIds[i].value = FloatValue(tv.delayFeedback ?: -1f)
            trackGlideRateIds[i].value = FloatValue(tv.glideRate)
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
        // Write degrees first, then length (acts as a release fence on the C++ side).
        val customProg = vibe.genre.customProgression
        if (customProg != null) {
            customProg.forEachIndexed { i, degree ->
                if (i < 8) {
                    synthController.setPluginControl(
                        PluginControlId(PULSAR_URI, "custom_progression_$i"),
                        IntValue(degree)
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
                lickDataIds[i * 3].value = FloatValue(step.scaleDegree.toFloat())
                lickDataIds[i * 3 + 1].value = FloatValue(step.duration)
                lickDataIds[i * 3 + 2].value = FloatValue(step.velocity)
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
        // In EXPLICIT mode (DJ app), playing is controlled by toggleGlobalPause, mix stays at 1.
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

    private fun pushEffectiveSends(deep: Float) {
        val vibe = vibeFlow.value
        val space = spaceId.value.asFloat()
        val floor = vibe.effects.deepFloor
        // SPACE boosts DEEP with a per-vibe floor: effectiveDeep = deep * (floor + space * (1 - floor))
        val effectiveDeep = deep * (floor + space * (1.0f - floor))
        for (i in 0 until 8) {
            val tv = vibe.tracks.getOrNull(i) ?: continue
            trackDelaySendIds[i].value = FloatValue(tv.delaySend * effectiveDeep)
            trackReverbSendIds[i].value = FloatValue(tv.reverbSend * effectiveDeep)
        }
    }

    /**
     * Pack and write arrangement data to engine atomics via SynthController.
     *
     * Layout for C++ engine arrays (must match orpheus_engine_routing.cpp and load_vibe()):
     *
     * section_data[s * 21 + field]:
     *   0=bars_min, 1=bars_max, 2=transition_bars, 3=recency_decay,
     *   4=macro_energy, 5=macro_complexity, 6=macro_space, 7=macro_mood,
     *   8=has_solo/solo_mode_id, 9..17=solo params (format depends on new vs legacy),
     *   12=bars_per_soloist_max, 13=solo_transition_bars, 14=improv_carryover,
     *   15=transition_count, 16=reserved, 17=reserved,
     *   18=comping_style_override (-1=no override), 19=comping_inversion_override, 20=chord_follow_override
     *
     * section_transitions[s * 8 * 2 + t * 2 + field]:
     *   0=targetIndex, 1=weight (up to 8 transitions per section)
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
            // [0]=bars_min, [1]=bars_max, [2]=transition_bars, [3]=recency_decay,
            // [4]=transition_count, [5]=energy, [6]=complexity, [7]=space, [8]=mood,
            // [9..17]=solo data (format depends on new vs legacy system)
            setSection(0, section.barsMin.toFloat())
            setSection(1, section.barsMax.toFloat())
            setSection(2, section.transitionBars.toFloat())
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

            // Transitions for this section (up to 8 × 2 floats)
            val transBase = s * 8 * 2
            section.transitions.forEachIndexed { t, tr ->
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 2}"),
                    FloatValue(tr.targetIndex.toFloat())
                )
                synthController.setPluginControl(
                    PluginControlId(PULSAR_URI, "section_transitions_${transBase + t * 2 + 1}"),
                    FloatValue(tr.weight)
                )
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
            tracks = List(8) { TrackVoice(engineEdm = Engine.VA, engineSpace = Engine.VA, role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic()) },
        )

        fun previewFeature(state: PulsarUiState = PulsarUiState(
            vibe = previewVibe,
        )): PulsarFeature =
            object : PulsarFeature {
                override val vibeList: List<Vibe> = listOf(previewVibe)
                override val arrangementStateFlow: StateFlow<PulsarArrangementState> = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
                override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(state)
                override val actions: PulsarPanelActions = PulsarPanelActions.EMPTY
            }

        @Composable
        fun feature(): PulsarFeature =
            synthFeature<PulsarViewModel, PulsarFeature>()
    }
}
