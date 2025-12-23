package org.balch.songe.features.lfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.presets.PresetLoader

/** UI state for the Hyper LFO panel. */
data class LfoUiState(
    val lfoA: Float = 0.0f,
    val lfoB: Float = 0.0f,
    val mode: HyperLfoMode = HyperLfoMode.OFF,
    val linkEnabled: Boolean = false
)

/** User intents for the LFO panel. */
private sealed interface LfoIntent {
    data class LfoA(val value: Float) : LfoIntent
    data class LfoB(val value: Float) : LfoIntent
    data class Mode(val mode: HyperLfoMode) : LfoIntent
    data class Link(val enabled: Boolean) : LfoIntent
    data class Restore(val state: LfoUiState) : LfoIntent
}

/**
 * ViewModel for the Hyper LFO panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(LfoViewModel::class)
@ContributesIntoMap(AppScope::class)
class LfoViewModel(
    private val engine: SongeEngine,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents =
        MutableSharedFlow<LfoIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uiState: StateFlow<LfoUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(LfoUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = LfoUiState()
            )

    init {
        viewModelScope.launch(dispatcherProvider.io) {
            applyFullState(uiState.value)

            presetLoader.presetFlow.collect { preset ->
                val lfoState =
                    LfoUiState(
                        lfoA = preset.hyperLfoA,
                        lfoB = preset.hyperLfoB,
                        mode =
                            try {
                                HyperLfoMode.valueOf(preset.hyperLfoMode)
                            } catch (e: Exception) {
                                HyperLfoMode.OFF
                            },
                        linkEnabled = preset.hyperLfoLink
                    )
                intents.tryEmit(LfoIntent.Restore(lfoState))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: LfoUiState, intent: LfoIntent): LfoUiState =
        when (intent) {
            is LfoIntent.LfoA ->
                state.copy(lfoA = intent.value)

            is LfoIntent.LfoB ->
                state.copy(lfoB = intent.value)

            is LfoIntent.Mode ->
                state.copy(mode = intent.mode)

            is LfoIntent.Link -> state.copy(linkEnabled = intent.enabled)
            is LfoIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: LfoIntent) {
        when (intent) {
            is LfoIntent.LfoA -> engine.setHyperLfoFreq(0, intent.value)
            is LfoIntent.LfoB -> engine.setHyperLfoFreq(1, intent.value)
            is LfoIntent.Mode -> engine.setHyperLfoMode(intent.mode.ordinal)
            is LfoIntent.Link -> engine.setHyperLfoLink(intent.enabled)
            is LfoIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: LfoUiState) {
        engine.setHyperLfoFreq(0, state.lfoA)
        engine.setHyperLfoFreq(1, state.lfoB)
        engine.setHyperLfoMode(state.mode.ordinal)
        engine.setHyperLfoLink(state.linkEnabled)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onLfoAChange(value: Float) {
        intents.tryEmit(LfoIntent.LfoA(value))
    }

    fun onLfoBChange(value: Float) {
        intents.tryEmit(LfoIntent.LfoB(value))
    }

    fun onModeChange(mode: HyperLfoMode) {
        intents.tryEmit(LfoIntent.Mode(mode))
    }

    fun onLinkChange(enabled: Boolean) {
        intents.tryEmit(LfoIntent.Link(enabled))
    }

    fun restoreState(state: LfoUiState) {
        intents.tryEmit(LfoIntent.Restore(state))
    }
}
