package org.balch.songe.features.delay

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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.presets.PresetLoader

/** UI state for the Mod Delay panel. */
data class DelayUiState(
    val time1: Float = 0.3f,
    val time2: Float = 0.3f,
    val mod1: Float = 0.0f,
    val mod2: Float = 0.0f,
    val feedback: Float = 0.5f,
    val mix: Float = 0.5f,
    val isLfoSource: Boolean = true,
    val isTriangleWave: Boolean = true
)

/** User intents for the Delay panel. */
private sealed interface DelayIntent {
    data class Time1(val value: Float) : DelayIntent
    data class Time2(val value: Float) : DelayIntent
    data class Mod1(val value: Float) : DelayIntent
    data class Mod2(val value: Float) : DelayIntent
    data class Feedback(val value: Float) : DelayIntent
    data class Mix(val value: Float) : DelayIntent
    data class Source(val isLfo: Boolean) : DelayIntent
    data class Waveform(val isTriangle: Boolean) : DelayIntent
    data class Restore(val state: DelayUiState) : DelayIntent
}

/**
 * ViewModel for the Mod Delay panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(DelayViewModel::class)
@ContributesIntoMap(AppScope::class)
class DelayViewModel(
    private val engine: SongeEngine,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents =
        MutableSharedFlow<DelayIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uiState: StateFlow<DelayUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(DelayUiState()) { state, intent -> reduce(state, intent) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DelayUiState()
            )

    init {
        applyFullState(uiState.value)

        viewModelScope.launch {
            presetLoader.presetFlow.collect { preset ->
                val delayState =
                    DelayUiState(
                        time1 = preset.delayTime1,
                        time2 = preset.delayTime2,
                        mod1 = preset.delayMod1,
                        mod2 = preset.delayMod2,
                        feedback = preset.delayFeedback,
                        mix = preset.delayMix,
                        isLfoSource = preset.delayModSourceIsLfo,
                        isTriangleWave = preset.delayLfoWaveformIsTriangle
                    )
                intents.tryEmit(DelayIntent.Restore(delayState))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DelayUiState, intent: DelayIntent): DelayUiState =
        when (intent) {
            is DelayIntent.Time1 ->
                state.copy(time1 = intent.value)

            is DelayIntent.Time2 ->
                state.copy(time2 = intent.value)

            is DelayIntent.Mod1 ->
                state.copy(mod1 = intent.value)

            is DelayIntent.Mod2 ->
                state.copy(mod2 = intent.value)

            is DelayIntent.Feedback ->
                state.copy(feedback = intent.value)

            is DelayIntent.Mix ->
                state.copy(mix = intent.value)

            is DelayIntent.Source ->
                state.copy(isLfoSource = intent.isLfo)

            is DelayIntent.Waveform ->
                state.copy(isTriangleWave = intent.isTriangle)

            is DelayIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: DelayIntent) {
        when (intent) {
            is DelayIntent.Time1 -> engine.setDelayTime(0, intent.value)
            is DelayIntent.Time2 -> engine.setDelayTime(1, intent.value)
            is DelayIntent.Mod1 -> engine.setDelayModDepth(0, intent.value)
            is DelayIntent.Mod2 -> engine.setDelayModDepth(1, intent.value)
            is DelayIntent.Feedback -> engine.setDelayFeedback(intent.value)
            is DelayIntent.Mix -> engine.setDelayMix(intent.value)
            is DelayIntent.Source -> {
                engine.setDelayModSource(0, intent.isLfo)
                engine.setDelayModSource(1, intent.isLfo)
            }

            is DelayIntent.Waveform -> engine.setDelayLfoWaveform(intent.isTriangle)
            is DelayIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: DelayUiState) {
        engine.setDelayTime(0, state.time1)
        engine.setDelayTime(1, state.time2)
        engine.setDelayModDepth(0, state.mod1)
        engine.setDelayModDepth(1, state.mod2)
        engine.setDelayFeedback(state.feedback)
        engine.setDelayMix(state.mix)
        engine.setDelayModSource(0, state.isLfoSource)
        engine.setDelayModSource(1, state.isLfoSource)
        engine.setDelayLfoWaveform(state.isTriangleWave)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onTime1Change(value: Float) {
        intents.tryEmit(DelayIntent.Time1(value))
    }

    fun onTime2Change(value: Float) {
        intents.tryEmit(DelayIntent.Time2(value))
    }

    fun onMod1Change(value: Float) {
        intents.tryEmit(DelayIntent.Mod1(value))
    }

    fun onMod2Change(value: Float) {
        intents.tryEmit(DelayIntent.Mod2(value))
    }

    fun onFeedbackChange(value: Float) {
        intents.tryEmit(DelayIntent.Feedback(value))
    }

    fun onMixChange(value: Float) {
        intents.tryEmit(DelayIntent.Mix(value))
    }

    fun onSourceChange(isLfo: Boolean) {
        intents.tryEmit(DelayIntent.Source(isLfo))
    }

    fun onWaveformChange(isTriangle: Boolean) {
        intents.tryEmit(DelayIntent.Waveform(isTriangle))
    }

    fun restoreState(state: DelayUiState) {
        intents.tryEmit(DelayIntent.Restore(state))
    }
}
