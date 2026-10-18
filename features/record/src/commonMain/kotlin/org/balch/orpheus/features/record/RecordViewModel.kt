package org.balch.orpheus.features.record

import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.features.record.playback.PlaybackState
import org.balch.orpheus.features.record.playback.TakePlayer
import org.balch.orpheus.features.record.store.TakeInfo
import org.balch.orpheus.features.record.store.TakeStore

@Immutable
data class RecordUiState(
    val recording: Boolean = false,
    val elapsedSec: Float = 0f,
    val level: Float = 0f,
    val takes: List<TakeInfo> = emptyList(),
    val selectedTakeId: String? = null,
    val pendingDeleteId: String? = null,
    val playback: PlaybackState = PlaybackState(),
)

@Immutable
data class RecordPanelActions(
    val toggleRecord: () -> Unit,
    val select: (String) -> Unit,
    val requestDelete: (String) -> Unit,
    val confirmDelete: () -> Unit,
    val cancelDelete: () -> Unit,
    val play: (String) -> Unit,
    val pause: () -> Unit,
    val seek: (Float) -> Unit,
) {
    companion object {
        val EMPTY = RecordPanelActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

interface RecordFeature : SynthFeature<RecordUiState, RecordPanelActions> {

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.RECORD
            override val title = "Record"
            override val markdown = """
                Records the final output to a take with the starting preset and a telemetry log
                (gates, control moves, MIDI notes) so an AI can review what happened.

                ## Controls
                - **Record button**: Start or stop a take (stops itself at 5 minutes).
                - **Takes**: Newest first. Select one to listen, or delete it with a confirmation.
            """.trimIndent()
            override val portControlKeys = emptyMap<String, String>()
        }
    }
}

@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(RecordFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<RecordFeature>())
class RecordViewModel(
    private val session: RecordSession,
    private val store: TakeStore,
    private val player: TakePlayer,
    masterLevel: MasterLevelSource,
    private val scope: FeatureCoroutineScope,
) : RecordFeature {

    private val log = logging("RecordViewModel")

    private val takes = MutableStateFlow<List<TakeInfo>>(emptyList())
    private val selectedTakeId = MutableStateFlow<String?>(null)
    private val pendingDeleteId = MutableStateFlow<String?>(null)

    // The player loads asynchronously, so its state lags a play request. Track the request itself.
    private var requestedTakeId: String? = null

    // The take being written is already on disk, so hide it until it is finished. Only
    // recomputed when the recording id or the list changes, not on every level tick.
    private val visibleTakes = combine(
        session.state.map { (it as? RecordState.Recording)?.takeId }.distinctUntilChanged(),
        takes,
    ) { recordingId, all -> all.filter { it.takeId != recordingId } }

    override val stateFlow: StateFlow<RecordUiState> = combine(
        session.state, masterLevel.level, visibleTakes, selectedTakeId, pendingDeleteId,
    ) { recState, level, visible, selected, pending ->
        val recording = recState as? RecordState.Recording
        RecordUiState(
            recording = recording != null,
            elapsedSec = recording?.elapsedSec ?: 0f,
            level = level,
            takes = visible,
            selectedTakeId = selected,
            pendingDeleteId = pending,
        )
    }.combine(player.state) { ui, playback -> ui.copy(playback = playback) }
        .stateIn(scope, SynthFeature.sharingStrategy, RecordUiState())

    override val actions = RecordPanelActions(
        toggleRecord = ::toggleRecord,
        select = { selectedTakeId.value = it },
        requestDelete = ::requestDelete,
        confirmDelete = ::confirmDelete,
        cancelDelete = { pendingDeleteId.value = null },
        play = ::play,
        pause = player::pause,
        seek = player::seek,
    )

    init {
        scope.launch { refreshTakes() }
        scope.launch {
            // An aborted take never emits takesChanged, so drop its folder from the list here.
            var wasRecording = false
            session.state.collect { recState ->
                val isRecording = recState is RecordState.Recording
                if (wasRecording && !isRecording) refreshTakes()
                wasRecording = isRecording
            }
        }
        scope.launch {
            session.takesChanged.collect { takeId ->
                refreshTakes()
                selectedTakeId.value = takeId
            }
        }
    }

    private fun recordingTakeId(): String? = (session.state.value as? RecordState.Recording)?.takeId

    private fun toggleRecord() {
        scope.launch {
            if (session.state.value is RecordState.Recording) {
                session.stop()
            } else {
                // Playback must not run into the take being recorded.
                stopPlayback()
                session.start()
            }
        }
    }

    private fun play(takeId: String) {
        if (takeId == recordingTakeId()) return
        val take = takes.value.firstOrNull { it.takeId == takeId } ?: return
        selectedTakeId.value = takeId
        requestedTakeId = takeId
        player.play(take)
    }

    private fun stopPlayback() {
        requestedTakeId = null
        player.stop()
    }

    private fun requestDelete(takeId: String) {
        if (takeId == recordingTakeId()) return
        pendingDeleteId.value = takeId
    }

    private fun confirmDelete() {
        val takeId = pendingDeleteId.value ?: return
        pendingDeleteId.value = null
        if (takeId == recordingTakeId()) return
        scope.launch {
            try {
                if (takeId == requestedTakeId || takeId == player.state.value.takeId) stopPlayback()
                store.delete(takeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error { "Could not delete $takeId: ${e.message}" }
            }
            if (selectedTakeId.value == takeId) selectedTakeId.value = null
            refreshTakes()
        }
    }

    private suspend fun refreshTakes() {
        try {
            val listed = store.list()
            takes.value = listed
            if (selectedTakeId.value !in listed.map { it.takeId }) selectedTakeId.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error { "Could not list takes: ${e.message}" }
        }
    }

    companion object {
        fun previewFeature(state: RecordUiState = RecordUiState()): RecordFeature =
            object : RecordFeature {
                override val stateFlow: StateFlow<RecordUiState> = MutableStateFlow(state)
                override val actions = RecordPanelActions.EMPTY
            }
    }
}
