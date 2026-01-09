package org.balch.orpheus.features.debug

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.util.ConsoleLogger
import org.balch.orpheus.util.LogEntry

/** UI state for the Debug bottom bar. */
data class DebugUiState(
    val peak: Float,
    val cpuLoad: Float,
    val logs: List<LogEntry> = emptyList()
)

/** Actions for the Debug bottom bar. */
data class DebugPanelActions(
    val onClearLogs: () -> Unit
) {
    companion object {
        val EMPTY = DebugPanelActions(
            onClearLogs = {}
        )
    }
}

typealias DebugFeature = SynthFeature<DebugUiState, DebugPanelActions>

/**
 * ViewModel for the Debug bottom bar.
 *
 * Combines engine monitoring flows with console logs into a unified UI state.
 */
@Inject
@ViewModelKey(DebugViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DebugViewModel(
    private val engine: SynthEngine,
    private val consoleLogger: ConsoleLogger
) : ViewModel(), DebugFeature {

    override val actions = DebugPanelActions(
        onClearLogs = ::onClearLogs
    )

    override val stateFlow: StateFlow<DebugUiState> = combine(
        engine.peakFlow,
        engine.cpuLoadFlow,
        consoleLogger.logsFlow
    ) { peak, cpuLoad, logs ->
        DebugUiState(
            peak = peak,
            cpuLoad = cpuLoad,
            logs = logs
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DebugUiState(0f, 0f,)
    )

    private fun onClearLogs() {
        consoleLogger.clear()
    }

    companion object {
        fun previewFeature(state: DebugUiState = DebugUiState(peak = 0.5f, cpuLoad = 12.5f)): DebugFeature =
            object : DebugFeature {
                override val stateFlow: StateFlow<DebugUiState> = MutableStateFlow(state)
                override val actions: DebugPanelActions = DebugPanelActions.EMPTY
            }

        @Composable
        fun feature(): DebugFeature =
            synthViewModel<DebugViewModel, DebugFeature>()
    }
}
