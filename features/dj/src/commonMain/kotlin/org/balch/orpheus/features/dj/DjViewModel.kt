package org.balch.orpheus.features.dj

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.DjDrop
import org.balch.orpheus.core.plugin.symbols.DjSource
import org.balch.orpheus.core.plugin.symbols.DjSymbol

@Immutable
@Serializable
data class DeckDropState(
    val dragActive: Boolean = false,
    val drop: DjDrop = DjDrop.NONE,
)

@Immutable
@Serializable
data class DjUiState(
    val wetA: Float = 0f,
    val wetB: Float = 0f,
    val sourceA: DjSource = DjSource.SYNTH,
    val sourceB: DjSource = DjSource.BASS,
    val velocityA: Float = 0f,
    val velocityB: Float = 0f,
    val frozenA: Boolean = false,
    val frozenB: Boolean = false,
    val lockedA: Boolean = false,
    val lockedB: Boolean = false,
    val crossfader: Float = 0.5f,
    val delaySend: Float = 0f,
    val reverbSend: Float = 0f,
    val decks: List<DeckDropState> = listOf(DeckDropState(), DeckDropState()),
    val zoneOrder: List<DjDrop> = listOf(DjDrop.FILTER, DjDrop.BRAKE, DjDrop.STUTTER, DjDrop.FREEZE),
)

@Immutable
data class DjPanelActions(
    val setWetA: (Float) -> Unit,
    val setWetB: (Float) -> Unit,
    val setSourceA: (Int) -> Unit,
    val setSourceB: (Int) -> Unit,
    val setCrossfader: (Float) -> Unit,
    val setDelaySend: (Float) -> Unit,
    val setReverbSend: (Float) -> Unit,
    val setPlatterDrag: (Int, Float) -> Unit,
    val setPlatterRelease: (Int) -> Unit,
    val toggleLock: (Int) -> Unit,
    val setDragActive: (deck: Int, active: Boolean) -> Unit,
    val setDrop: (deck: Int, drop: DjDrop) -> Unit,
) {
    companion object {
        val EMPTY = DjPanelActions(
            setWetA = {},
            setWetB = {},
            setSourceA = {},
            setSourceB = {},
            setCrossfader = {},
            setDelaySend = {},
            setReverbSend = {},
            setPlatterDrag = { _, _ -> },
            setPlatterRelease = {},
            toggleLock = {},
            setDragActive = { _, _ -> },
            setDrop = { _, _ -> },
        )
    }
}

/**
 * Picks 4 unique drops for the zone strip, weighted by [DjDrop.weight].
 * Entries with weight 0 (currently only [DjDrop.NONE]) are excluded.
 * `internal` so [DjViewModelDropTest] can verify the invariants directly.
 */
internal fun pickZoneOrder(): List<DjDrop> {
    val pool = DjDrop.entries
        .filter { it.weight > 0 }
        .flatMap { drop -> List(drop.weight) { drop } }
        .toMutableList()
    val picked = mutableListOf<DjDrop>()
    while (picked.size < 4 && pool.isNotEmpty()) {
        val drop = pool.random()
        picked += drop
        pool.removeAll { it == drop }  // no duplicates in the strip
    }
    return picked
}

/** User intents for the DJ panel. */
private sealed interface DjIntent {
    data class SetWetA(val value: Float) : DjIntent
    data class SetWetB(val value: Float) : DjIntent
    data class SetSourceA(val source: DjSource) : DjIntent
    data class SetSourceB(val source: DjSource) : DjIntent
    data class SetCrossfader(val value: Float) : DjIntent
    data class SetDelaySend(val value: Float) : DjIntent
    data class SetReverbSend(val value: Float) : DjIntent
    data class PhysicsTick(
        val velocityA: Float, val velocityB: Float,
        val frozenA: Boolean, val frozenB: Boolean,
    ) : DjIntent
    data class ToggleLock(val deck: Int) : DjIntent
    data class SetDragActive(val deck: Int, val active: Boolean) : DjIntent
    data class SetDrop(val deck: Int, val drop: DjDrop) : DjIntent
    data class ReshuffleZones(val order: List<DjDrop>) : DjIntent
}

/**
 * ViewModel for the DJ turntable panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 * Includes a physics coroutine that simulates platter motor/friction behavior at ~60Hz.
 */
@Inject
@ClassKey
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class DjViewModel(
    synthController: SynthController,
    private val dispatchers: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
    persistence: FeatureStatePersistence,
    private val restoreStrategy: RestoreStrategy,
) : DjFeature {

    // Control flows for DJ plugin ports
    private val wetAId = synthController.controlFlow(DjSymbol.WET_A.controlId)
    private val wetBId = synthController.controlFlow(DjSymbol.WET_B.controlId)
    private val sourceAId = synthController.controlFlow(DjSymbol.SOURCE_A.controlId)
    private val sourceBId = synthController.controlFlow(DjSymbol.SOURCE_B.controlId)
    private val velocityAId = synthController.controlFlow(DjSymbol.VELOCITY_A.controlId)
    private val velocityBId = synthController.controlFlow(DjSymbol.VELOCITY_B.controlId)
    private val frozenAId = synthController.controlFlow(DjSymbol.FROZEN_A.controlId)
    private val frozenBId = synthController.controlFlow(DjSymbol.FROZEN_B.controlId)
    private val crossfaderId = synthController.controlFlow(DjSymbol.CROSSFADER.controlId)
    private val delaySendId = synthController.controlFlow(DjSymbol.DELAY_SEND.controlId)
    private val reverbSendId = synthController.controlFlow(DjSymbol.REVERB_SEND.controlId)
    private val dropAId = synthController.controlFlow(DjSymbol.DROP_A.controlId)
    private val dropBId = synthController.controlFlow(DjSymbol.DROP_B.controlId)
    private val setDropAPort = dropAId.intSetter()
    private val setDropBPort = dropBId.intSetter()

    // Physics state — mutable vars updated by UI gestures and physics tick
    // dragVelocity = instantaneous angular velocity from the drag gesture
    // currentVelocity = the smoothed velocity sent to C++ (persists across ticks)
    private var touchingA = false
    private var touchingB = false
    private var dragVelocityA = 0f
    private var dragVelocityB = 0f
    private var currentVelocityA = 0f
    private var currentVelocityB = 0f
    private var lockedA = false
    private var lockedB = false
    // Mirrors decks[i].drop != NONE. Set from the setDrop action on the gesture
    // thread, read by the physics loop. Non-atomic Float reads/writes of velocity
    // already exist across threads here — a plain Bool is no worse.
    private var lockedDropA = false
    private var lockedDropB = false

    // Direct setters for physics-driven ports (send to C++ engine)
    private val wetASetter = wetAId.floatSetter()
    private val wetBSetter = wetBId.floatSetter()
    private val setVelocityA = velocityAId.floatSetter()
    private val setVelocityB = velocityBId.floatSetter()
    private val setFrozenA = frozenAId.boolSetter()
    private val setFrozenB = frozenBId.boolSetter()

    // Physics tick flow — emits into the MVI pipeline to update UiState.
    // Capacity 8: gesture cleanup emits multiple intents in sequence (SetDrop, SetDragActive)
    // and the physics tick emits every 16 ms. Capacity 1 would drop intents if the downstream
    // collector hasn't drained between consecutive tryEmit calls.
    private val physicsIntentFlow = MutableSharedFlow<DjIntent>(extraBufferCapacity = 8)

    override val actions = DjPanelActions(
        setWetA = { v -> currentWetA = v; wetASetter(v) },
        setWetB = { v -> currentWetB = v; wetBSetter(v) },
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
        toggleLock = { deck -> physicsIntentFlow.tryEmit(DjIntent.ToggleLock(deck)) },
        setDragActive = { deck, active ->
            physicsIntentFlow.tryEmit(DjIntent.SetDragActive(deck, active))
        },
        setDrop = { deck, drop ->
            val locked = drop != DjDrop.NONE
            when (deck) {
                0 -> {
                    setDropAPort(drop.id)
                    lockedDropA = locked
                    // Instant velocity bump on lock-in so the drop kicks in with energy
                    // instead of waiting for MOTOR_DECAY to ramp up over ~0.5s.
                    if (locked) currentVelocityA = DROP_MOTOR_SPEED
                }
                1 -> {
                    setDropBPort(drop.id)
                    lockedDropB = locked
                    if (locked) currentVelocityB = DROP_MOTOR_SPEED
                }
            }
            physicsIntentFlow.tryEmit(DjIntent.SetDrop(deck, drop))
        },
    )

    // Track current wet levels for gating the physics loop
    private var currentWetA = 0f
    private var currentWetB = 0f

    private val physicsTickFlow: Flow<DjIntent.PhysicsTick> = flow {
        while (true) {
            delay(16)

            val deckAActive = currentWetA > kMixBypassThreshold || touchingA
            val deckBActive = currentWetB > kMixBypassThreshold || touchingB

            // Skip physics when both decks are fully dry and nobody is touching
            if (!deckAActive && !deckBActive) continue

            // Deck A — only run physics when this deck is active.
            // Motor target while the finger is lifted: DROP_MOTOR_SPEED when a drop
            // is locked, MOTOR_SPEED (normal playback) otherwise. While the finger
            // is down, the drag velocity wins — holding still at the fader deadspot
            // (drag == 0) lerps the platter toward 0, so the deadspot feels like a
            // brake rather than a phantom motor boost.
            if (deckAActive) {
                val motorTargetA = if (lockedDropA) DROP_MOTOR_SPEED else MOTOR_SPEED
                val motorRampA = if (lockedDropA) LOCKED_MOTOR_RAMP else MOTOR_DECAY
                if (touchingA) {
                    currentVelocityA = lerp(currentVelocityA, dragVelocityA, SCRATCH_RESPONSE)
                } else {
                    currentVelocityA = lerp(currentVelocityA, motorTargetA, motorRampA)
                }
                setVelocityA(currentVelocityA)
                setFrozenA(touchingA || lockedA)
            }

            // Deck B — same rules as deck A.
            if (deckBActive) {
                val motorTargetB = if (lockedDropB) DROP_MOTOR_SPEED else MOTOR_SPEED
                val motorRampB = if (lockedDropB) LOCKED_MOTOR_RAMP else MOTOR_DECAY
                if (touchingB) {
                    currentVelocityB = lerp(currentVelocityB, dragVelocityB, SCRATCH_RESPONSE)
                } else {
                    currentVelocityB = lerp(currentVelocityB, motorTargetB, motorRampB)
                }
                setVelocityB(currentVelocityB)
                setFrozenB(touchingB || lockedB)
            }

            emit(
                DjIntent.PhysicsTick(
                    velocityA = if (deckAActive) currentVelocityA else 0f,
                    velocityB = if (deckBActive) currentVelocityB else 0f,
                    frozenA = touchingA || lockedA,
                    frozenB = touchingB || lockedB,
                )
            )
        }
    }
    .flowOn(dispatchers.default)

    // Control changes + physics -> DjIntent
    private val controlIntents = merge(
        physicsTickFlow,
        physicsIntentFlow,
        wetAId.map { DjIntent.SetWetA(it.asFloat()) },
        wetBId.map { DjIntent.SetWetB(it.asFloat()) },
        sourceAId.map {
            val id = it.asInt()
            DjIntent.SetSourceA(DjSource.entries.first { s -> s.sourceId == id })
        },
        sourceBId.map {
            val id = it.asInt()
            DjIntent.SetSourceB(DjSource.entries.first { s -> s.sourceId == id })
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
            .flowOn(dispatchers.default)
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
                initialValue = DjUiState()
            )

    init {
        persistence.bind(
            stateFlow = stateFlow,
            serializer = DjUiState.serializer(),
            reader = { it.lastDjJson },
            writer = { prefs, json -> prefs.copy(lastDjJson = json) },
            restoreStrategy = restoreStrategy,
            stripTransient = {
                it.copy(
                    wetA = 0f, wetB = 0f,
                    velocityA = 0f, velocityB = 0f,
                    frozenA = false, frozenB = false,
                    lockedA = false, lockedB = false,
                    crossfader = 0.5f, delaySend = 0f, reverbSend = 0f,
                    decks = listOf(DeckDropState(), DeckDropState()),
                    zoneOrder = pickZoneOrder(),
                )
            },
            onRestore = { saved ->
                sourceAId.value = IntValue(saved.sourceA.sourceId)
                sourceBId.value = IntValue(saved.sourceB.sourceId)
            },
        )
    }

    init {
        // Seed the first zoneOrder with a random pick so the very first drag
        // isn't always the same 4 drops.
        physicsIntentFlow.tryEmit(DjIntent.ReshuffleZones(pickZoneOrder()))

        // Reshuffle zone order whenever the zone strip transitions from active to idle.
        // Picks 4 unique drops from the weighted library, so every drag offers a
        // different flavor combo. BRAKE and FREEZE (weight 1) appear ~3x less often
        // than the other six drops (weight 3).
        scope.launch(dispatchers.default) {
            var wasActive = false
            stateFlow.collect { s ->
                val isActive = s.decks.any { it.dragActive || it.drop != DjDrop.NONE }
                if (wasActive && !isActive) {
                    physicsIntentFlow.tryEmit(DjIntent.ReshuffleZones(pickZoneOrder()))
                }
                wasActive = isActive
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DjUiState, intent: DjIntent): DjUiState =
        when (intent) {
            is DjIntent.SetWetA -> state.copy(wetA = intent.value)
            is DjIntent.SetWetB -> state.copy(wetB = intent.value)
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
            is DjIntent.ToggleLock -> {
                when (intent.deck) {
                    0 -> { lockedA = !lockedA; state.copy(lockedA = lockedA) }
                    1 -> { lockedB = !lockedB; state.copy(lockedB = lockedB) }
                    else -> state
                }
            }
            is DjIntent.SetDragActive -> state.copy(
                decks = state.decks.mapIndexed { i, d ->
                    if (i == intent.deck) d.copy(dragActive = intent.active) else d
                }
            )
            is DjIntent.SetDrop -> state.copy(
                decks = state.decks.mapIndexed { i, d ->
                    if (i == intent.deck) d.copy(drop = intent.drop) else d
                }
            )
            is DjIntent.ReshuffleZones -> state.copy(zoneOrder = intent.order)
        }

    companion object {
        private val log = logging("DjViewModel")
        private const val MOTOR_SPEED = 1.0f       // normal playback target (no drop locked)
        private const val DROP_MOTOR_SPEED = 3.5f  // boosted target while a drop is locked
        private const val MOTOR_DECAY = 0.05f      // slow return to motor (released)
        private const val LOCKED_MOTOR_RAMP = 0.18f // faster pull toward DROP_MOTOR_SPEED (~180 ms)
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
