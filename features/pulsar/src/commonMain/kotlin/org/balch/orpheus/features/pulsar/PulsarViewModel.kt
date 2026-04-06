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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo

@Serializable
@Immutable
data class PulsarUiState(
    val playing: Boolean = false,
    val bpm: Float = 128f,
    val vibe: Vibe,
    val energy: Float = 0.5f,
    val complexity: Float = 0.3f,
    val space: Float = 0.4f,
    val mood: Float = 0.5f,
    val delaySend: Float = 0.0f,
    val reverbSend: Float = 0.0f,
    val rootNote: Int = 2,
    val scaleIndex: Int = 0,
    val mix: Float = 0.0f,
    val percMix: Float = 0.7f,
    val envelopeMode: Int = 0,  // 0=AD, 1=Tides, 2=Blend (energy-driven)
    val selectedTrack: Int? = null,
    val trackEnginesEdm: List<Int> = listOf(21, 22, 23, 9, 14, 14, 17, 20),
    val trackEnginesSpace: List<Int> = listOf(20, 17, 23, 19, 6, 14, 17, 19),
)

@Immutable
data class PulsarPanelActions(
    val togglePlaying: () -> Unit = {},
    val setVibe: (Vibe) -> Unit = {},
    val setEnergy: (Float) -> Unit = {},
    val setComplexity: (Float) -> Unit = {},
    val setSpace: (Float) -> Unit = {},
    val setMood: (Float) -> Unit = {},
    val setBpm: (Float) -> Unit = {},
    val setDelaySend: (Float) -> Unit = {},
    val setReverbSend: (Float) -> Unit = {},
    val setRootNote: (Int) -> Unit = {},
    val setScale: (Int) -> Unit = {},
    val setMix: (Float) -> Unit = {},
    val setPercMix: (Float) -> Unit = {},
    val setEnvelopeMode: (Int) -> Unit = {},
    val selectTrack: (Int?) -> Unit = {},
    val setTrackEngineEdm: (Int, Int) -> Unit = { _, _ -> },
    val setTrackEngineSpace: (Int, Int) -> Unit = { _, _ -> },
) {
    companion object {
        val EMPTY = PulsarPanelActions()
    }
}

/** User intents for the Pulsar panel. */
private sealed interface PulsarIntent {
    data class Playing(val value: Boolean) : PulsarIntent
    data class VibeChange(val value: Vibe) : PulsarIntent
    data class Energy(val value: Float) : PulsarIntent
    data class Complexity(val value: Float) : PulsarIntent
    data class Space(val value: Float) : PulsarIntent
    data class Mood(val value: Float) : PulsarIntent
    data class Bpm(val value: Float) : PulsarIntent
    data class DelaySend(val value: Float) : PulsarIntent
    data class ReverbSend(val value: Float) : PulsarIntent
    data class RootNote(val value: Int) : PulsarIntent
    data class Scale(val value: Int) : PulsarIntent
    data class Mix(val value: Float) : PulsarIntent
    data class PercMix(val value: Float) : PulsarIntent
    data class EnvelopeMode(val value: Int) : PulsarIntent
    data class SelectedTrack(val value: Int?) : PulsarIntent
    data class TrackEngineEdm(val track: Int, val engine: Int) : PulsarIntent
    data class TrackEngineSpace(val track: Int, val engine: Int) : PulsarIntent
}

interface PulsarFeature : SynthFeature<PulsarUiState, PulsarPanelActions> {
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
                - **DELAY**: Send level to delay effect.
                - **REVERB**: Send level to reverb effect.
            """.trimIndent()

            override val portControlKeys = mapOf(
                PulsarSymbol.PLAYING.controlId.key to "Start/stop playback (0=stopped, 1=playing)",
                PulsarSymbol.ENERGY.controlId.key to "Pattern intensity (0..1)",
                PulsarSymbol.COMPLEXITY.controlId.key to "Rhythmic density (0..1)",
                PulsarSymbol.SPACE.controlId.key to "Stereo width and space (0..1)",
                PulsarSymbol.MOOD.controlId.key to "Tonal character (0..1)",
                PulsarSymbol.BPM.controlId.key to "Tempo in BPM (0..300)",
                PulsarSymbol.DELAY_SEND.controlId.key to "Delay send level (0..1)",
                PulsarSymbol.REVERB_SEND.controlId.key to "Reverb send level (0..1)",
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
    private val globalTempo: GlobalTempo,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) : PulsarFeature {

    private val log = logging("PulsarVM")

    // ═══════════════════════════════════════════════════════════
    // Control flows
    // ═══════════════════════════════════════════════════════════
    private val playingId = synthController.controlFlow(PulsarSymbol.PLAYING.controlId)
    private val vibeGenerationId = synthController.controlFlow(PulsarSymbol.VIBE_GENERATION.controlId)
    private val energyId = synthController.controlFlow(PulsarSymbol.ENERGY.controlId)
    private val complexityId = synthController.controlFlow(PulsarSymbol.COMPLEXITY.controlId)
    private val spaceId = synthController.controlFlow(PulsarSymbol.SPACE.controlId)
    private val moodId = synthController.controlFlow(PulsarSymbol.MOOD.controlId)
    private val bpmId = synthController.controlFlow(PulsarSymbol.BPM.controlId)
    private val delaySendId = synthController.controlFlow(PulsarSymbol.DELAY_SEND.controlId)
    private val reverbSendId = synthController.controlFlow(PulsarSymbol.REVERB_SEND.controlId)
    private val rootNoteId = synthController.controlFlow(PulsarSymbol.ROOT_NOTE.controlId)
    private val scaleId = synthController.controlFlow(PulsarSymbol.SCALE.controlId)
    private val mixId = synthController.controlFlow(PulsarSymbol.MIX.controlId)
    private val percMixId = synthController.controlFlow(PulsarSymbol.PERC_MIX.controlId)
    private val envelopeModeId = synthController.controlFlow(PulsarSymbol.ENVELOPE_MODE.controlId)
    private val seedId = synthController.controlFlow(PulsarSymbol.SEED.controlId)
    private val lickMutationId = synthController.controlFlow(PulsarSymbol.LICK_MUTATION.controlId)
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
    private val trackPercussiveIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_PERCUSSIVE.ordinal + i].controlId)
    }
    private val trackMacroIds = (0..7).map { t ->
        (0..15).map { m ->
            synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_MACRO_ENERGY_VOL_MIN.ordinal + t * 16 + m].controlId)
        }
    }
    private val genreDensityIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.GENRE_DENSITY_0.ordinal + i].controlId)
    }
    private val genreSwingId = synthController.controlFlow(PulsarSymbol.GENRE_SWING.controlId)
    private val genreGhostProbId = synthController.controlFlow(PulsarSymbol.GENRE_GHOST_PROB.controlId)
    private val genreNoteRangeLowId = synthController.controlFlow(PulsarSymbol.GENRE_NOTE_RANGE_LOW.controlId)
    private val genreNoteRangeHighId = synthController.controlFlow(PulsarSymbol.GENRE_NOTE_RANGE_HIGH.controlId)
    private val genreRhythmPatternId = synthController.controlFlow(PulsarSymbol.GENRE_RHYTHM_PATTERN.controlId)
    private val lickLengthId = synthController.controlFlow(PulsarSymbol.LICK_LENGTH.controlId)
    private val lickDataIds = (0..95).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.LICK_DATA_0.ordinal + i].controlId)
    }

    private val selectedTrackFlow = MutableStateFlow<Int?>(null)
    private val vibeFlow = MutableStateFlow(PulsarVibes.all().first())
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
    }

    // ═══════════════════════════════════════════════════════════
    // Actions
    // ═══════════════════════════════════════════════════════════
    override val actions = PulsarPanelActions(
        togglePlaying = {
            val current = playingId.value.asInt()
            playingId.value = IntValue(if (current != 0) 0 else 1)
        },
        setVibe = { vibe -> applyVibe(vibe) },
        setEnergy = energyId.floatSetter(),
        setComplexity = complexityId.floatSetter(),
        setSpace = spaceId.floatSetter(),
        setMood = moodId.floatSetter(),
        setBpm = { bpm ->
            bpmId.value = FloatValue(bpm)
            globalTempo.setBpm(bpm.toDouble())
        },
        setDelaySend = delaySendId.floatSetter(),
        setReverbSend = reverbSendId.floatSetter(),
        setRootNote = rootNoteId.intSetter(),
        setScale = scaleId.intSetter(),
        setMix = { value ->
            mixId.value = FloatValue(value)
            if (value > 0f && playingId.value.asInt() == 0) {
                playingId.value = IntValue(1)
            }
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
    )

    // ═══════════════════════════════════════════════════════════
    // State flow
    // ═══════════════════════════════════════════════════════════
    private val controlIntents = merge(
        playingId.map { PulsarIntent.Playing(it.asInt() != 0) },
        vibeFlow.map { PulsarIntent.VibeChange(it) },
        energyId.map { PulsarIntent.Energy(it.asFloat()) },
        complexityId.map { PulsarIntent.Complexity(it.asFloat()) },
        spaceId.map { PulsarIntent.Space(it.asFloat()) },
        moodId.map { PulsarIntent.Mood(it.asFloat()) },
        bpmId.map { PulsarIntent.Bpm(it.asFloat()) },
        delaySendId.map { PulsarIntent.DelaySend(it.asFloat()) },
        reverbSendId.map { PulsarIntent.ReverbSend(it.asFloat()) },
        rootNoteId.map { PulsarIntent.RootNote(it.asInt()) },
        scaleId.map { PulsarIntent.Scale(it.asInt()) },
        mixId.map { PulsarIntent.Mix(it.asFloat()) },
        percMixId.map { PulsarIntent.PercMix(it.asFloat()) },
        envelopeModeId.map { PulsarIntent.EnvelopeMode(it.asInt()) },
        selectedTrackFlow.map { PulsarIntent.SelectedTrack(it) },
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
                    is PulsarIntent.VibeChange -> state.copy(vibe = intent.value)
                    is PulsarIntent.Energy -> state.copy(energy = intent.value)
                    is PulsarIntent.Complexity -> state.copy(complexity = intent.value)
                    is PulsarIntent.Space -> state.copy(space = intent.value)
                    is PulsarIntent.Mood -> state.copy(mood = intent.value)
                    is PulsarIntent.Bpm -> state.copy(bpm = intent.value)
                    is PulsarIntent.DelaySend -> state.copy(delaySend = intent.value)
                    is PulsarIntent.ReverbSend -> state.copy(reverbSend = intent.value)
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
        // Apply the full vibe recipe first (sets engines, genre, lick, macro defaults)
        applyVibe(saved.vibe)
        // Then override with saved macro values
        energyId.value = FloatValue(saved.energy)
        complexityId.value = FloatValue(saved.complexity)
        spaceId.value = FloatValue(saved.space)
        moodId.value = FloatValue(saved.mood)
        bpmId.value = FloatValue(saved.bpm)
        globalTempo.setBpm(saved.bpm.toDouble())
        delaySendId.value = FloatValue(saved.delaySend)
        reverbSendId.value = FloatValue(saved.reverbSend)
        percMixId.value = FloatValue(saved.percMix)
        envelopeModeId.value = IntValue(saved.envelopeMode)
        log.debug { "Restored Pulsar state: vibe=${saved.vibe.name}, bpm=${saved.bpm}" }
    }

    private suspend fun saveState(state: PulsarUiState) {
        // Strip transient fields; mix always starts at 0 (user dials it in)
        val toSave = state.copy(playing = false, selectedTrack = null, mix = 0f)
        val json = persistJson.encodeToString(PulsarUiState.serializer(), toSave)
        appPreferencesRepository.update { it.copy(lastPulsarJson = json) }
    }

    // ═══════════════════════════════════════════════════════════
    // Vibe application
    // ═══════════════════════════════════════════════════════════

    /**
     * Push the entire vibe recipe to C++. Called from setVibe action and restoreSavedState.
     */
    private fun applyVibe(vibe: Vibe) {
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
            trackPercussiveIds[i].value = IntValue(if (tv.isPercussive) 1 else 0)
            pushMacroMap(i, tv.macroMap)
        }

        // Push genre profile
        vibe.genre.baseDensity.forEachIndexed { i, d -> genreDensityIds[i].value = FloatValue(d) }
        genreSwingId.value = FloatValue(vibe.genre.swingAmount)
        genreGhostProbId.value = FloatValue(vibe.genre.ghostProbability)
        genreNoteRangeLowId.value = IntValue(vibe.genre.noteRangeLow)
        genreNoteRangeHighId.value = IntValue(vibe.genre.noteRangeHigh)
        genreRhythmPatternId.value = IntValue(vibe.genre.rhythmPattern)

        // Push lick (data first, length last as release fence)
        val lick = vibe.lick
        if (lick != null) {
            lick.steps.forEachIndexed { i, step ->
                lickDataIds[i * 3].value = FloatValue(step.scaleDegree.toFloat())
                lickDataIds[i * 3 + 1].value = FloatValue(step.duration)
                lickDataIds[i * 3 + 2].value = FloatValue(step.velocity)
            }
            lickMutationId.value = FloatValue(vibe.lickMutation)
            lickLengthId.value = IntValue(lick.steps.size)
        } else {
            lickLengthId.value = IntValue(0)
        }

        seedId.value = IntValue(vibe.seed)
        envelopeModeId.value = IntValue(vibe.envelopeMode)

        // Set macro defaults
        rootNoteId.value = IntValue(vibe.rootNote)
        scaleId.value = IntValue(vibe.scaleIndex)
        bpmId.value = FloatValue(vibe.bpm)
        globalTempo.setBpm(vibe.bpm.toDouble())
        energyId.value = FloatValue(vibe.energy)
        complexityId.value = FloatValue(vibe.complexity)
        spaceId.value = FloatValue(vibe.space)
        moodId.value = FloatValue(vibe.mood)

        // Trigger vibe reload (MUST be last)
        vibeGenerationId.value = IntValue(vibeGenerationId.value.asInt() + 1)

        // Update local vibe index for UI state
        vibeFlow.value = vibe
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
        ids[10].value = FloatValue(map.spaceReverbSend.min)
        ids[11].value = FloatValue(map.spaceReverbSend.max)
        ids[12].value = FloatValue(map.moodHarmonics.min)
        ids[13].value = FloatValue(map.moodHarmonics.max)
        ids[14].value = FloatValue(map.moodTimbre.min)
        ids[15].value = FloatValue(map.moodTimbre.max)
    }

    companion object {
        fun previewFeature(state: PulsarUiState = PulsarUiState(
            vibe = PulsarVibes.all().first(),
        )): PulsarFeature =
            object : PulsarFeature {
                override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(state)
                override val actions: PulsarPanelActions = PulsarPanelActions.EMPTY
            }

        @Composable
        fun feature(): PulsarFeature =
            synthFeature<PulsarViewModel, PulsarFeature>()
    }
}
