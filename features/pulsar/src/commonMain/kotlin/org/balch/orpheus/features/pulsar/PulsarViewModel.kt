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
    val sceneIndex: Int = 2,
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
    val setScene: (Int) -> Unit = {},
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
    data class Scene(val value: Int) : PulsarIntent
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
                Beat machine with scene-based presets. Controls energy, complexity, space, and mood
                to shape rhythmic patterns generated by the Pulsar engine.

                ## Controls
                - **PLAY**: Start/stop the beat machine.
                - **BPM**: Tempo in beats per minute.
                - **SCENE**: Select a rhythmic scene preset.
                - **ENERGY**: Intensity/velocity of the pattern.
                - **COMPLEXITY**: Rhythmic density and variation.
                - **SPACE**: Stereo width and reverb amount.
                - **MOOD**: Tonal character of the drum sounds.
                - **DELAY**: Send level to delay effect.
                - **REVERB**: Send level to reverb effect.
            """.trimIndent()

            override val portControlKeys = mapOf(
                PulsarSymbol.PLAYING.controlId.key to "Start/stop playback (0=stopped, 1=playing)",
                PulsarSymbol.SCENE.controlId.key to "Scene preset index",
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
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) : PulsarFeature {

    private val log = logging("PulsarVM")

    // ═══════════════════════════════════════════════════════════
    // Control flows
    // ═══════════════════════════════════════════════════════════
    private val playingId = synthController.controlFlow(PulsarSymbol.PLAYING.controlId)
    private val sceneId = synthController.controlFlow(PulsarSymbol.SCENE.controlId)
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
    private val trackEdmIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_EDM.ordinal + i * 2].controlId)
    }
    private val trackSpaceIds = (0..7).map { i ->
        synthController.controlFlow(PulsarSymbol.entries[PulsarSymbol.TRACK_0_ENGINE_SPACE.ordinal + i * 2].controlId)
    }

    private val selectedTrackFlow = MutableStateFlow<Int?>(null)
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
        setScene = { scene ->
            sceneId.value = IntValue(scene)
            val defaults = KitDefaults.forScene(scene)
            defaults.edm.forEachIndexed { i, eng -> trackEdmIds[i].value = IntValue(eng) }
            defaults.space.forEachIndexed { i, eng -> trackSpaceIds[i].value = IntValue(eng) }
            rootNoteId.value = IntValue(defaults.rootNote)
            scaleId.value = IntValue(defaults.scaleIndex)
            bpmId.value = FloatValue(defaults.bpm)
            globalTempo.setBpm(defaults.bpm.toDouble())
        },
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
        sceneId.map { PulsarIntent.Scene(it.asInt()) },
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
            .scan(PulsarUiState(bpm = globalTempo.getBpm().toFloat())) { state, intent ->
                when (intent) {
                    is PulsarIntent.Playing -> state.copy(playing = intent.value)
                    is PulsarIntent.Scene -> state.copy(sceneIndex = intent.value)
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
                initialValue = PulsarUiState(bpm = globalTempo.getBpm().toFloat())
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
        sceneId.value = IntValue(saved.sceneIndex)
        energyId.value = FloatValue(saved.energy)
        complexityId.value = FloatValue(saved.complexity)
        spaceId.value = FloatValue(saved.space)
        moodId.value = FloatValue(saved.mood)
        bpmId.value = FloatValue(saved.bpm)
        globalTempo.setBpm(saved.bpm.toDouble())
        delaySendId.value = FloatValue(saved.delaySend)
        reverbSendId.value = FloatValue(saved.reverbSend)
        rootNoteId.value = IntValue(saved.rootNote)
        scaleId.value = IntValue(saved.scaleIndex)
        percMixId.value = FloatValue(saved.percMix)
        envelopeModeId.value = IntValue(saved.envelopeMode)
        saved.trackEnginesEdm.forEachIndexed { i, eng -> trackEdmIds[i].value = IntValue(eng) }
        saved.trackEnginesSpace.forEachIndexed { i, eng -> trackSpaceIds[i].value = IntValue(eng) }
        log.debug { "Restored Pulsar state: scene=${saved.sceneIndex}, bpm=${saved.bpm}" }
    }

    private suspend fun saveState(state: PulsarUiState) {
        // Strip transient fields; mix always starts at 0 (user dials it in)
        val toSave = state.copy(playing = false, selectedTrack = null, mix = 0f)
        val json = persistJson.encodeToString(PulsarUiState.serializer(), toSave)
        appPreferencesRepository.update { it.copy(lastPulsarJson = json) }
    }

    companion object {
        data class KitDefaults(
            val edm: List<Int>,
            val space: List<Int>,
            val rootNote: Int,
            val scaleIndex: Int,
            val bpm: Float,
        ) {
            companion object {
                fun forScene(scene: Int): KitDefaults = when (scene) {
                    0 -> KitDefaults(  // Deep Space — A minor, 70 BPM
                        edm   = listOf(20, 17, 23, 9, 6, 14, 11, 20),
                        space = listOf(20, 18, 23, 19, 6, 19, 13, 19),
                        rootNote = 9, scaleIndex = 0, bpm = 70f,
                    )
                    1 -> KitDefaults(  // Chillwave — C major, 100 BPM
                        edm   = listOf(21, 22, 23, 8, 14, 6, 13, 20),
                        space = listOf(20, 17, 23, 19, 6, 6, 13, 19),
                        rootNote = 0, scaleIndex = 1, bpm = 100f,
                    )
                    2 -> KitDefaults(  // Cosmic Techno — D minor, 128 BPM
                        edm   = listOf(21, 22, 23, 9, 14, 14, 17, 20),
                        space = listOf(20, 17, 23, 19, 6, 14, 17, 19),
                        rootNote = 2, scaleIndex = 0, bpm = 128f,
                    )
                    3 -> KitDefaults(  // Dog House — E Phrygian, 85 BPM
                        edm   = listOf(21, 22, 23, 9, 6, 19, 11, 20),
                        space = listOf(21, 22, 23, 19, 6, 19, 11, 19),
                        rootNote = 4, scaleIndex = 3, bpm = 85f,
                    )
                    4 -> KitDefaults(  // Artemis II — G major, 120 BPM
                        edm   = listOf(21, 22, 23, 10, 14, 6, 11, 20),
                        space = listOf(21, 22, 23, 10, 14, 6, 13, 15),
                        rootNote = 7, scaleIndex = 1, bpm = 120f,
                    )
                    else -> forScene(0)
                }
            }
        }

        fun previewFeature(state: PulsarUiState = PulsarUiState()): PulsarFeature =
            object : PulsarFeature {
                override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(state)
                override val actions: PulsarPanelActions = PulsarPanelActions.EMPTY
            }

        @Composable
        fun feature(): PulsarFeature =
            synthFeature<PulsarViewModel, PulsarFeature>()
    }
}
