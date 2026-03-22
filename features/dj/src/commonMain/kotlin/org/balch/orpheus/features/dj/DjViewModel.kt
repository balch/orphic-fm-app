package org.balch.orpheus.features.dj

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.symbols.DjSource
import org.balch.orpheus.core.plugin.symbols.DjSymbol

@Immutable
data class DjUiState(
    val mix: Float = 0f,
    val sourceA: DjSource = DjSource.SYNTH,
    val sourceB: DjSource = DjSource.BASS,
    val velocityA: Float = 1f,
    val velocityB: Float = 1f,
    val frozenA: Boolean = false,
    val frozenB: Boolean = false,
    val crossfader: Float = 0.5f,
    val delaySend: Float = 0f,
    val reverbSend: Float = 0f,
)

@Immutable
data class DjPanelActions(
    val setMix: (Float) -> Unit,
    val setSourceA: (Int) -> Unit,
    val setSourceB: (Int) -> Unit,
    val setCrossfader: (Float) -> Unit,
    val setDelaySend: (Float) -> Unit,
    val setReverbSend: (Float) -> Unit,
    val setPlatterDrag: (Int, Float) -> Unit,
    val setPlatterRelease: (Int) -> Unit,
) {
    companion object {
        val EMPTY = DjPanelActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {})
    }
}

/** User intents for the DJ panel. */
private sealed interface DjIntent {
    data class SetMix(val value: Float) : DjIntent
    data class SetSourceA(val source: DjSource) : DjIntent
    data class SetSourceB(val source: DjSource) : DjIntent
    data class SetCrossfader(val value: Float) : DjIntent
    data class SetDelaySend(val value: Float) : DjIntent
    data class SetReverbSend(val value: Float) : DjIntent
    data class PhysicsTick(
        val velocityA: Float, val velocityB: Float,
        val frozenA: Boolean, val frozenB: Boolean,
    ) : DjIntent
}

/**
 * ViewModel for the DJ turntable panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 * Includes a physics coroutine that simulates platter motor/friction behavior at ~60Hz.
 */
@Inject
@ClassKey(DjViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class DjViewModel(
    synthController: SynthController,
    private val dispatchers: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : DjFeature {

    // Control flows for DJ plugin ports
    private val mixId = synthController.controlFlow(DjSymbol.MIX.controlId)
    private val sourceAId = synthController.controlFlow(DjSymbol.SOURCE_A.controlId)
    private val sourceBId = synthController.controlFlow(DjSymbol.SOURCE_B.controlId)
    private val velocityAId = synthController.controlFlow(DjSymbol.VELOCITY_A.controlId)
    private val velocityBId = synthController.controlFlow(DjSymbol.VELOCITY_B.controlId)
    private val frozenAId = synthController.controlFlow(DjSymbol.FROZEN_A.controlId)
    private val frozenBId = synthController.controlFlow(DjSymbol.FROZEN_B.controlId)
    private val crossfaderId = synthController.controlFlow(DjSymbol.CROSSFADER.controlId)
    private val delaySendId = synthController.controlFlow(DjSymbol.DELAY_SEND.controlId)
    private val reverbSendId = synthController.controlFlow(DjSymbol.REVERB_SEND.controlId)

    // Physics state — mutable vars updated by UI gestures and physics tick
    // dragVelocity = instantaneous angular velocity from the drag gesture
    // currentVelocity = the smoothed velocity sent to C++ (persists across ticks)
    private var touchingA = false
    private var touchingB = false
    private var dragVelocityA = 0f
    private var dragVelocityB = 0f
    private var currentVelocityA = MOTOR_SPEED
    private var currentVelocityB = MOTOR_SPEED

    // Direct setters for physics-driven ports (send to C++ engine)
    private val mixSetter = mixId.floatSetter()
    private val setVelocityA = velocityAId.floatSetter()
    private val setVelocityB = velocityBId.floatSetter()
    private val setFrozenA = frozenAId.boolSetter()
    private val setFrozenB = frozenBId.boolSetter()

    // Physics tick flow — emits into the MVI pipeline to update UiState
    private val physicsIntentFlow = MutableSharedFlow<DjIntent>(extraBufferCapacity = 1)

    override val actions = DjPanelActions(
        setMix = { v -> currentMix = v; mixSetter(v) },
        setSourceA = sourceAId.intSetter(),
        setSourceB = sourceBId.intSetter(),
        setCrossfader = crossfaderId.floatSetter(),
        setDelaySend = delaySendId.floatSetter(),
        setReverbSend = reverbSendId.floatSetter(),
        setPlatterDrag = { deck, velocity ->
            when (deck) {
                0 -> { touchingA = true; dragVelocityA = velocity }
                1 -> { touchingB = true; dragVelocityB = velocity }
            }
        },
        setPlatterRelease = { deck ->
            when (deck) {
                0 -> touchingA = false
                1 -> touchingB = false
            }
        },
    )

    // Track current mix for gating the physics loop
    private var currentMix = 0f

    init {
        // Physics simulation at ~60Hz — drives velocity/frozen to C++ engine.
        // Gated on mix > 0 or active touch to avoid unnecessary setPort calls when DJ is off.
        scope.launch(dispatchers.default) {
            while (true) {
                delay(16)

                // Skip physics when DJ is fully bypassed and nobody is touching
                if (currentMix <= kMixBypassThreshold && !touchingA && !touchingB) continue

                // Deck A
                if (touchingA) {
                    // While touching: smoothly follow drag velocity (responsive but not jerky)
                    currentVelocityA = lerp(currentVelocityA, dragVelocityA, SCRATCH_RESPONSE)
                } else {
                    // Released: smoothly return to motor speed
                    currentVelocityA = lerp(currentVelocityA, MOTOR_SPEED, MOTOR_DECAY)
                }
                setVelocityA(currentVelocityA)
                setFrozenA(touchingA)

                // Deck B
                if (touchingB) {
                    currentVelocityB = lerp(currentVelocityB, dragVelocityB, SCRATCH_RESPONSE)
                } else {
                    currentVelocityB = lerp(currentVelocityB, MOTOR_SPEED, MOTOR_DECAY)
                }
                setVelocityB(currentVelocityB)
                setFrozenB(touchingB)

                // Emit to MVI so UiState reflects velocity/frozen for UI display
                physicsIntentFlow.tryEmit(
                    DjIntent.PhysicsTick(
                        velocityA = currentVelocityA,
                        velocityB = currentVelocityB,
                        frozenA = touchingA,
                        frozenB = touchingB,
                    )
                )
            }
        }
    }

    // Control changes + physics -> DjIntent
    private val controlIntents = merge(
        physicsIntentFlow,
        mixId.map { DjIntent.SetMix(it.asFloat()) },
        sourceAId.map {
            val sources = DjSource.entries
            val index = it.asInt().coerceIn(0, sources.size - 1)
            DjIntent.SetSourceA(sources[index])
        },
        sourceBId.map {
            val sources = DjSource.entries
            val index = it.asInt().coerceIn(0, sources.size - 1)
            DjIntent.SetSourceB(sources[index])
        },
        crossfaderId.map { DjIntent.SetCrossfader(it.asFloat()) },
        delaySendId.map { DjIntent.SetDelaySend(it.asFloat()) },
        reverbSendId.map { DjIntent.SetReverbSend(it.asFloat()) },
    )

    override val stateFlow: StateFlow<DjUiState> =
        controlIntents
            .scan(DjUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatchers.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = DjUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DjUiState, intent: DjIntent): DjUiState =
        when (intent) {
            is DjIntent.SetMix -> state.copy(mix = intent.value)
            is DjIntent.SetSourceA -> state.copy(sourceA = intent.source)
            is DjIntent.SetSourceB -> state.copy(sourceB = intent.source)
            is DjIntent.SetCrossfader -> state.copy(crossfader = intent.value)
            is DjIntent.SetDelaySend -> state.copy(delaySend = intent.value)
            is DjIntent.SetReverbSend -> state.copy(reverbSend = intent.value)
            is DjIntent.PhysicsTick -> state.copy(
                velocityA = intent.velocityA,
                velocityB = intent.velocityB,
                frozenA = intent.frozenA,
                frozenB = intent.frozenB,
            )
        }

    companion object {
        private const val MOTOR_SPEED = 1.0f
        private const val MOTOR_DECAY = 0.05f      // slow return to motor (released)
        private const val SCRATCH_RESPONSE = 0.7f   // near-instant follow of drag gesture
        private const val kMixBypassThreshold = 0.001f

        private fun lerp(current: Float, target: Float, alpha: Float): Float =
            current + (target - current) * alpha

        fun previewFeature(state: DjUiState = DjUiState()): DjFeature =
            object : DjFeature {
                override val stateFlow: StateFlow<DjUiState> = MutableStateFlow(state)
                override val actions: DjPanelActions = DjPanelActions.EMPTY
            }

        @Composable
        fun feature(): DjFeature =
            synthFeature<DjViewModel, DjFeature>()
    }
}
