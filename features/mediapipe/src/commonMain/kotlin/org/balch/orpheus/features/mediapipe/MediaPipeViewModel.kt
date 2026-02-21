package org.balch.orpheus.features.mediapipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.gestures.AslCategory
import org.balch.orpheus.core.gestures.AslEvent
import org.balch.orpheus.core.gestures.SwipeDirection
import org.balch.orpheus.core.gestures.AslInteractionEngine
import org.balch.orpheus.core.gestures.AslSign
import org.balch.orpheus.core.gestures.ConductorEvent
import org.balch.orpheus.core.gestures.ConductorInteractionEngine
import org.balch.orpheus.core.gestures.GestureInterpreter
import org.balch.orpheus.core.gestures.GestureMode
import org.balch.orpheus.core.gestures.GestureState
import org.balch.orpheus.core.gestures.InteractionPhase
import org.balch.orpheus.core.mediapipe.CameraFrame
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.mediapipe.TrackedHand
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import com.diamondedge.logging.logging
import org.balch.orpheus.core.plugin.symbols.BenderSymbol
import org.balch.orpheus.core.plugin.symbols.VizSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol

@Immutable
data class MediaPipeUiState(
    val isEnabled: Boolean = false,
    val isTracking: Boolean = false,
    val hands: List<TrackedHand> = emptyList(),
    val gestureStates: List<GestureState> = emptyList(),
    val cameraFrame: CameraFrame? = null,
    val isBending: Boolean = false,
    val heldVoiceIndices: Set<Int> = emptySet(),
    val selectedTarget: AslSign? = null,
    val selectedParam: AslSign? = null,
    val modePrefix: AslSign? = null,
    val interactionPhase: InteractionPhase = InteractionPhase.IDLE,
    val gestureMode: GestureMode = GestureMode.ASL,
    val remoteAdjustArmed: Boolean = false,
    val selectedDuoIndex: Int? = null,
    val selectedQuadIndex: Int? = null,
)

@Immutable
data class MediaPipePanelActions(
    val toggleEnabled: () -> Unit,
    val toggleHold: (voiceIndex: Int) -> Unit,
) {
    companion object {
        val EMPTY = MediaPipePanelActions({}, {})
    }
}

interface MediaPipeFeature : SynthFeature<MediaPipeUiState, MediaPipePanelActions> {
    /** Audio engine for shader effects -- null in preview. */
    val engine: SynthEngine?
        get() = null

    /** Emits swipe directions from thumbs-up gesture for panel switching. */
    val panelSwipeEvents: SharedFlow<SwipeDirection>
        get() = MutableSharedFlow() // default no-op for preview

    /** Per-string bend amounts (-1..1) from Maestro Mode, for UI deflection. */
    val stringBends: StateFlow<List<Float>>
        get() = MutableStateFlow(listOf(0f, 0f, 0f, 0f)) // default no-op for preview

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.ASL_MAESTRO
            override val title = "Gesture Control"
            override val markdown = """
                Camera-based hand tracking gesture control using MediaPipe.
                Tracks hand landmarks and recognizes ASL signs to select voices
                and control synth parameters via pinch gestures.

                ## Controls
                - **Enable**: Toggle camera hand tracking on/off.

                ## ASL Signs
                - **Numbers 1-8**: Select voice.
                - **Letters (M, S, B, W)**: Select parameter to adjust.
                - **Pinch (other hand)**: Trigger voice gate or adjust parameter.
                - **A**: Deselect / clear.
            """.trimIndent()

            override val portControlKeys = emptyMap<String, String>()
        }
    }
}

/**
 * ViewModel for the MediaPipe hand tracking gesture control panel.
 *
 * Reads from HandTracker, interprets gestures via GestureInterpreter,
 * and routes ASL sign recognition + pinch gestures to synth parameters
 * via AslInteractionEngine and SynthController.
 */
@Inject
@ClassKey(MediaPipeViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class MediaPipeViewModel(
    private val handTracker: HandTracker,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val _engine: SynthEngine,
) : ViewModel(), MediaPipeFeature {

    override val engine: SynthEngine get() = _engine

    private val _panelSwipeEvents = MutableSharedFlow<SwipeDirection>(extraBufferCapacity = 4)
    override val panelSwipeEvents: SharedFlow<SwipeDirection> = _panelSwipeEvents

    private val _stringBends = MutableStateFlow(listOf(0f, 0f, 0f, 0f))
    override val stringBends: StateFlow<List<Float>> = _stringBends

    private val log = logging("MediaPipeVM")
    private val gestureInterpreter = GestureInterpreter()
    private val aslEngine = AslInteractionEngine()
    private val conductorEngine = ConductorInteractionEngine()
    private val _gestureMode = MutableStateFlow(GestureMode.ASL)
    private var lastConductorToggleMs = 0L // cooldown prevents re-toggle bounce
    private val conductorToggleCooldownMs = 1500L

    private val _isEnabled = MutableStateFlow(false)
    private val _heldVoiceIndices = MutableStateFlow<Set<Int>>(emptySet())
    private val _selectedTarget = MutableStateFlow<AslSign?>(null)
    private val _selectedParam = MutableStateFlow<AslSign?>(null)
    private val _modePrefix = MutableStateFlow<AslSign?>(null)
    private val _interactionPhase = MutableStateFlow(InteractionPhase.IDLE)
    private val _isBending = MutableStateFlow(false)

    // Cached gesture states from event-processing combine, reused by stateFlow combine
    // to avoid running the classifier twice per frame.
    private val _cachedGestures = MutableStateFlow<List<GestureState>>(emptyList())

    // Duo/quad sub-selection indices (set by D/Q prefix + number)
    private var selectedDuoIndex: Int? = null
    private var selectedQuadIndex: Int? = null

    // Hand presence hysteresis: prevents single dropped frames from triggering deactivate
    private var handActive = false
    private var missCount = 0

    init {
        // Track hold state from any source (UI, MediaPipe, AI) for display and toggle logic
        viewModelScope.launch {
            synthController.onHoldChange.collect { event ->
                _heldVoiceIndices.value = if (event.holding) {
                    _heldVoiceIndices.value + event.voiceIndex
                } else {
                    _heldVoiceIndices.value - event.voiceIndex
                }
            }
        }
    }

    override val actions = MediaPipePanelActions(
        toggleEnabled = { toggleTracking() },
        toggleHold = { vi ->
            val currentlyHeld = vi in _heldVoiceIndices.value
            synthController.emitHoldChange(vi, !currentlyHeld, ControlEventOrigin.UI)
        },
    )

    init {
        // Process gesture events via AslInteractionEngine in a dedicated coroutine
        // that runs for the ViewModel's entire lifetime, independent of UI subscription
        // state. This ensures events (voice gates, parameter adjustments) are never
        // lost during brief UI unsubscriptions (e.g., configuration changes).
        viewModelScope.launch(dispatcherProvider.default) {
            combine(
                _isEnabled,
                handTracker.results,
            ) { enabled, result ->
                if (!enabled) {
                    if (handActive) {
                        handActive = false
                        missCount = 0
                        deactivateGestureControls()
                    }
                    return@combine
                }

                if (result == null || result.hands.isEmpty()) {
                    // Hysteresis: only deactivate after several consecutive misses
                    if (handActive) {
                        missCount++
                        if (missCount >= MISS_THRESHOLD) {
                            handActive = false
                            missCount = 0
                            deactivateGestureControls()
                        }
                    }
                    return@combine
                }

                // At least one hand detected -- reset miss counter, activate if needed
                missCount = 0
                if (!handActive) {
                    handActive = true
                }

                // Interpret each hand independently and cache for stateFlow reuse
                val gestures = result.hands.map { hand ->
                    gestureInterpreter.interpret(
                        hand.landmarks, hand.handedness,
                        hand.gestureName, hand.gestureConfidence,
                    )
                }
                _cachedGestures.value = gestures

                @OptIn(ExperimentalTime::class)
                val timestampMs = Clock.System.now().toEpochMilliseconds()

                when (_gestureMode.value) {
                    GestureMode.ASL -> {
                        val events = aslEngine.update(gestures, timestampMs)
                        for (event in events) {
                            dispatchAslEvent(event)
                        }
                        // Update ASL state flows for UI
                        _selectedTarget.value = aslEngine.selectedTarget
                        _selectedParam.value = aslEngine.selectedParam
                        _modePrefix.value = aslEngine.modePrefix
                        _interactionPhase.value = aslEngine.phase
                        _isBending.value = aslEngine.phase == InteractionPhase.CONTROLLING
                    }
                    GestureMode.CONDUCTOR -> {
                        // Exit conductor: fist (A/S) to return to ASL mode.
                        // ILY is entry-only — using it as exit too caused toggle bounce.
                        val signerHand = gestures.firstOrNull {
                            it.aslSign != null && it.aslConfidence >= 0.7f
                        }
                        val exitSign = signerHand?.aslSign
                        if (exitSign == AslSign.LETTER_A || exitSign == AslSign.LETTER_S) {
                            if (timestampMs - lastConductorToggleMs > conductorToggleCooldownMs) {
                                lastConductorToggleMs = timestampMs
                                log.info { "CONDUCTOR exit via $exitSign" }
                                toggleGestureMode()
                                return@combine
                            }
                        }
                        val events = conductorEngine.update(gestures, timestampMs)
                        for (event in events) {
                            dispatchConductorEvent(event)
                        }
                        // Swipe detection runs in Maestro Mode too, but suppressed
                        // when modifier fingers are active to prevent accidental triggers.
                        if (!conductorEngine.isAnyPinkyTouching && !conductorEngine.isAnyRingTouching) {
                            val swipeEvents = aslEngine.checkSwipe(gestures, timestampMs)
                            for (event in swipeEvents) {
                                dispatchAslEvent(event)
                            }
                        } else {
                            // Reset swipe state so stale palmX doesn't cause false trigger on release
                            aslEngine.checkSwipe(emptyList(), timestampMs)
                        }
                    }
                }
            }.collect {}
        }
    }

    override val stateFlow: StateFlow<MediaPipeUiState> =
        combine(
            _isEnabled,
            handTracker.results,
            handTracker.cameraFrame,
            combine(
                _heldVoiceIndices,
                _selectedTarget,
                _selectedParam,
                combine(_modePrefix, _interactionPhase, _gestureMode) { prefix, phase, mode ->
                    Triple(prefix, phase, mode)
                },
            ) { held, target, param, (prefix, phase, mode) ->
                AslUiExtras(held, target, param, prefix, phase, mode)
            },
            combine(_isBending, _cachedGestures) { b, g -> b to g },
        ) { enabled, result, frame, extras, (bending, cachedGestures) ->
            if (!enabled || result == null || result.hands.isEmpty()) {
                MediaPipeUiState(
                    isEnabled = enabled,
                    isTracking = false,
                    cameraFrame = if (enabled) frame else null,
                    heldVoiceIndices = extras.heldIndices,
                    selectedTarget = extras.selectedTarget,
                    selectedParam = extras.selectedParam,
                    modePrefix = extras.modePrefix,
                    interactionPhase = extras.interactionPhase,
                    gestureMode = extras.gestureMode,
                    remoteAdjustArmed = aslEngine.remoteAdjustArmed,
                    selectedDuoIndex = selectedDuoIndex,
                    selectedQuadIndex = selectedQuadIndex,
                )
            } else {
                MediaPipeUiState(
                    isEnabled = enabled,
                    isTracking = true,
                    hands = result.hands,
                    gestureStates = cachedGestures,
                    cameraFrame = frame,
                    isBending = bending,
                    heldVoiceIndices = extras.heldIndices,
                    selectedTarget = extras.selectedTarget,
                    selectedParam = extras.selectedParam,
                    modePrefix = extras.modePrefix,
                    interactionPhase = extras.interactionPhase,
                    gestureMode = extras.gestureMode,
                    remoteAdjustArmed = aslEngine.remoteAdjustArmed,
                    selectedDuoIndex = selectedDuoIndex,
                    selectedQuadIndex = selectedQuadIndex,
                )
            }
        }
            .flowOn(dispatcherProvider.default)
            .stateIn(
                scope = viewModelScope,
                started = sharingStrategy,
                initialValue = MediaPipeUiState(),
            )

    private data class AslUiExtras(
        val heldIndices: Set<Int>,
        val selectedTarget: AslSign?,
        val selectedParam: AslSign?,
        val modePrefix: AslSign?,
        val interactionPhase: InteractionPhase,
        val gestureMode: GestureMode,
    )

    /** Map AslEvent to synth controller calls. */
    private fun dispatchAslEvent(event: AslEvent) {
        log.debug { "dispatch $event" }
        when (event) {
            is AslEvent.VoiceGateOn -> {
                synthController.emitPulseStart(event.voiceIndex)
            }
            is AslEvent.VoiceGateOff -> {
                synthController.emitPulseEnd(event.voiceIndex)
            }
            is AslEvent.HoldToggle -> {
                // Double-pinch or thumbs up: toggle hold
                val currentlyHeld = event.voiceIndex in _heldVoiceIndices.value
                synthController.emitHoldChange(
                    event.voiceIndex, !currentlyHeld, ControlEventOrigin.MEDIAPIPE,
                )
            }
            is AslEvent.HoldOff -> {
                // Thumbs down: hold off
                synthController.emitHoldChange(
                    event.voiceIndex, false, ControlEventOrigin.MEDIAPIPE,
                )
            }
            is AslEvent.ParameterAdjust -> {
                adjustParameter(event.paramSign, event.delta)
            }
            is AslEvent.SystemParamSet -> {
                val controlId = resolveControlId(event.sign, event.sign) ?: return
                synthController.setPluginControl(
                    controlId,
                    PortValue.FloatValue(event.value.coerceIn(0f, 1f)),
                    ControlEventOrigin.MEDIAPIPE,
                )
            }
            is AslEvent.TargetSelected -> {
                // State update handled via selectedTarget flow
            }
            is AslEvent.TargetDeselected -> {
                // State update handled via selectedTarget flow
            }
            is AslEvent.DuoSelected -> {
                selectedDuoIndex = event.duoIndex
            }
            is AslEvent.QuadSelected -> {
                selectedQuadIndex = event.quadIndex
            }
            is AslEvent.EnvSpeedAdjust -> {
                adjustEnvSpeed(event.deltaZ)
            }
            is AslEvent.PanelSwipe -> {
                _panelSwipeEvents.tryEmit(event.direction)
            }
            is AslEvent.ToggleConductorMode -> {
                @OptIn(ExperimentalTime::class)
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastConductorToggleMs > conductorToggleCooldownMs) {
                    lastConductorToggleMs = now
                    log.info { "CONDUCTOR enter via ILY" }
                    toggleGestureMode()
                }
            }
        }
    }

    /** Cycle order for duo mod source: OFF → LFO → FM → FLUX → OFF. */
    private val modSourceCycleOrder = listOf(
        ModSource.OFF, ModSource.LFO, ModSource.VOICE_FM, ModSource.FLUX,
    )

    /** Track current mod source per quad for cycling. */
    private val currentModSource = arrayOf(ModSource.OFF, ModSource.OFF)

    private fun dispatchConductorEvent(event: ConductorEvent) {
        when (event) {
            is ConductorEvent.StringGateOn -> {
                val (v0, v1) = ConductorInteractionEngine.voicesForString(event.stringIndex)
                synthController.emitPulseStart(v0)
                synthController.emitPulseStart(v1)
            }
            is ConductorEvent.StringGateOff -> {
                val (v0, v1) = ConductorInteractionEngine.voicesForString(event.stringIndex)
                synthController.emitPulseEnd(v0)
                synthController.emitPulseEnd(v1)
            }
            is ConductorEvent.StringBendSet -> {
                _engine.setStringBend(event.stringIndex, event.bendAmount, 0.5f)
                // Update per-string bend state for UI deflection
                _stringBends.value = _stringBends.value.toMutableList().apply {
                    set(event.stringIndex, event.bendAmount)
                }
                // Update viz knobs so string UI animates
                val vizValue = (event.bendAmount + 1f) / 2f
                if (event.stringIndex == 0) {
                    synthController.emitControlChange(VizSymbol.KNOB_1.controlId.key, vizValue, ControlEventOrigin.MEDIAPIPE)
                } else if (event.stringIndex == 3) {
                    synthController.emitControlChange(VizSymbol.KNOB_2.controlId.key, vizValue, ControlEventOrigin.MEDIAPIPE)
                }
            }
            is ConductorEvent.StringRelease -> {
                _engine.releaseStringBend(event.stringIndex)
                // Reset per-string bend state for UI spring-back
                _stringBends.value = _stringBends.value.toMutableList().apply {
                    set(event.stringIndex, 0f)
                }

                if (event.stringIndex == 0) {
                    synthController.emitControlChange(VizSymbol.KNOB_1.controlId.key, 0.5f, ControlEventOrigin.MEDIAPIPE)
                } else if (event.stringIndex == 3) {
                    synthController.emitControlChange(VizSymbol.KNOB_2.controlId.key, 0.5f, ControlEventOrigin.MEDIAPIPE)
                }
            }
            is ConductorEvent.BendSet -> {
                synthController.emitBendChange(event.value)
            }
            is ConductorEvent.HoldSet -> {
                synthController.setPluginControl(
                    VoiceSymbol.quadHold(event.quadIndex).controlId,
                    PortValue.FloatValue(event.value),
                    ControlEventOrigin.MEDIAPIPE,
                )
            }
            is ConductorEvent.ModSourceCycle -> {
                val qi = event.quadIndex
                val current = currentModSource[qi]
                val currentIdx = modSourceCycleOrder.indexOf(current)
                val next = modSourceCycleOrder[(currentIdx + 1) % modSourceCycleOrder.size]
                currentModSource[qi] = next
                // Apply to both duos on this quad (qi*2 and qi*2+1, but capped at duo count)
                val duoStart = qi * 2
                for (di in duoStart..(duoStart + 1).coerceAtMost(3)) {
                    synthController.setPluginControl(
                        VoiceSymbol.duoModSource(di).controlId,
                        PortValue.IntValue(next.ordinal),
                        ControlEventOrigin.MEDIAPIPE,
                    )
                }
            }
            is ConductorEvent.ModSourceLevelSet -> {
                val duoStart = event.quadIndex * 2
                for (di in duoStart..(duoStart + 1).coerceAtMost(3)) {
                    synthController.setPluginControl(
                        VoiceSymbol.duoModSourceLevel(di).controlId,
                        PortValue.FloatValue(event.value),
                        ControlEventOrigin.MEDIAPIPE,
                    )
                }
            }
            is ConductorEvent.DynamicsSet -> {
                synthController.setPluginControl(
                    VoiceSymbol.quadVolume(event.quadIndex).controlId,
                    PortValue.FloatValue(event.value),
                    ControlEventOrigin.MEDIAPIPE,
                )
            }
            is ConductorEvent.TimbreSet -> {
                for (di in 0..3) {
                    synthController.setPluginControl(
                        VoiceSymbol.duoSharpness(di).controlId,
                        PortValue.FloatValue(event.value),
                        ControlEventOrigin.MEDIAPIPE,
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun toggleGestureMode() {
        // Flush current engine
        when (_gestureMode.value) {
            GestureMode.ASL -> {
                val ts = Clock.System.now().toEpochMilliseconds()
                val flush = aslEngine.update(emptyList(), ts)
                for (e in flush) dispatchAslEvent(e)
                aslEngine.reset()
            }
            GestureMode.CONDUCTOR -> {
                val flush = conductorEngine.reset()
                for (e in flush) dispatchConductorEvent(e)
            }
        }
        // Switch mode
        _gestureMode.value = when (_gestureMode.value) {
            GestureMode.ASL -> GestureMode.CONDUCTOR
            GestureMode.CONDUCTOR -> GestureMode.ASL
        }
        // Reset UI state
        _selectedTarget.value = null
        _selectedParam.value = null
        _modePrefix.value = null
        _interactionPhase.value = InteractionPhase.IDLE
        _isBending.value = false
    }

    /**
     * Adjust a synth parameter based on the selected target and param sign.
     * Reads the current value, applies the delta (scaled), and clamps to the
     * parameter's range: -1..1 for bipolar params (bend), 0..1 for all others.
     */
    private fun adjustParameter(paramSign: AslSign, delta: Float) {
        val target = _selectedTarget.value
        if (target == null) { log.debug { "adjustParameter - no target" }; return }
        val controlId = resolveControlId(target, paramSign)
        if (controlId == null) { log.debug { "adjustParameter - no controlId for target=$target param=$paramSign" }; return }
        val isBipolar = paramSign == AslSign.LETTER_B
        val default = if (isBipolar) 0f else 0.5f
        val range = if (isBipolar) -1f..1f else 0f..1f
        val current = synthController.getPluginControl(controlId)?.asFloat() ?: default
        val newValue = (current + delta * PARAM_ADJUST_SCALE).coerceIn(range)
        log.debug { "adjustParameter $controlId delta=$delta current=$current -> $newValue" }
        synthController.setPluginControl(
            controlId,
            PortValue.FloatValue(newValue),
            ControlEventOrigin.MEDIAPIPE,
        )
    }

    /**
     * Adjust envelope speed for the selected voice target via Z-depth delta.
     * Pushing hand toward camera = faster (higher value), pulling away = slower.
     */
    private fun adjustEnvSpeed(deltaZ: Float) {
        val target = _selectedTarget.value ?: return
        val voiceIndices = resolveVoiceIndices(target)
        if (voiceIndices.isEmpty()) return
        for (vi in voiceIndices) {
            val controlId = VoiceSymbol.envSpeed(vi).controlId
            val current = synthController.getPluginControl(controlId)?.asFloat() ?: 0.5f
            val newValue = (current + deltaZ * ENV_SPEED_Z_SCALE).coerceIn(0f, 1f)
            synthController.setPluginControl(controlId, PortValue.FloatValue(newValue), ControlEventOrigin.MEDIAPIPE)
        }
    }

    /** Resolve voice indices for a target, respecting duo/quad selection. */
    private fun resolveVoiceIndices(target: AslSign): List<Int> {
        return when {
            selectedDuoIndex != null -> {
                val di = selectedDuoIndex!!
                listOf(di * 2, di * 2 + 1)
            }
            selectedQuadIndex != null -> {
                val qi = selectedQuadIndex!!
                (qi * 4 until qi * 4 + 4).toList()
            }
            target.category == AslCategory.NUMBER -> {
                val vi = target.voiceIndex() ?: return emptyList()
                listOf(vi)
            }
            else -> emptyList()
        }
    }

    /**
     * Resolve a control port ID for a given target+param ASL sign combination.
     *
     * Mapping:
     * - Voice number (NUM_1-8) + param letter → per-voice parameter
     * - D (duo prefix) + param letter → per-duo parameter (requires selectedDuoIndex)
     * - Q (quad prefix) + param letter → per-quad parameter (requires selectedQuadIndex)
     * - System sign (V, C, Y) → global parameter (param is the target itself)
     */
    private fun resolveControlId(target: AslSign, param: AslSign): PluginControlId? {
        return when {
            // Voice-level params: target is a number sign (1-8)
            // Auto-derive duo (vi/2) and quad (vi/4) from voice index
            target.category == AslCategory.NUMBER -> {
                val vi = target.voiceIndex() ?: return null
                val di = selectedDuoIndex ?: (vi / 2)  // explicit D prefix overrides auto-derive
                val qi = selectedQuadIndex ?: (vi / 4)  // explicit Q prefix overrides auto-derive
                when (param) {
                    // Duo-level params (auto-derived from voice)
                    AslSign.LETTER_M -> VoiceSymbol.duoMorph(di).controlId
                    AslSign.LETTER_S -> VoiceSymbol.duoSharpness(di).controlId
                    AslSign.LETTER_L -> VoiceSymbol.duoModSourceLevel(di).controlId
                    // Quad-level params (auto-derived from voice)
                    AslSign.LETTER_H -> VoiceSymbol.quadHold(qi).controlId
                    AslSign.LETTER_W -> VoiceSymbol.quadVolume(qi).controlId
                    // Voice-level params
                    AslSign.LETTER_B -> BenderSymbol.BEND.controlId
                    else -> null
                }
            }
            // System params: the target IS the param (direct sign)
            target.category == AslCategory.SYSTEM -> {
                when (target) {
                    AslSign.LETTER_V -> VoiceSymbol.VIBRATO.controlId
                    AslSign.LETTER_C -> VoiceSymbol.COUPLING.controlId
                    AslSign.LETTER_Y -> VoiceSymbol.TOTAL_FEEDBACK.controlId
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun toggleTracking() {
        val newEnabled = !_isEnabled.value
        _isEnabled.value = newEnabled
        if (newEnabled && handTracker.isAvailable) {
            handTracker.start()
        } else {
            handTracker.stop()
            if (handActive) {
                handActive = false
                missCount = 0
                deactivateGestureControls()
            }
        }
    }

    /** Deactivate gesture controls and reset ASL engine state. */
    @OptIn(ExperimentalTime::class)
    private fun deactivateGestureControls() {
        log.info { "deactivateGestureControls (mode=${_gestureMode.value})" }
        // Flush pending events (e.g., gate-off for voices still gated via pinch)
        val timestampMs = Clock.System.now().toEpochMilliseconds()
        val flushEvents = aslEngine.update(emptyList(), timestampMs)
        for (event in flushEvents) {
            dispatchAslEvent(event)
        }
        aslEngine.reset()
        // Also flush conductor engine if active
        val conductorFlush = conductorEngine.reset()
        for (event in conductorFlush) {
            dispatchConductorEvent(event)
        }
        // Preserve Maestro Mode on tracking loss — only explicit fist exits conductor.
        // ASL mode resets fully since selection state depends on continuous tracking.
        if (_gestureMode.value == GestureMode.ASL) {
            _selectedTarget.value = null
            _selectedParam.value = null
            _modePrefix.value = null
            _interactionPhase.value = InteractionPhase.IDLE
            selectedDuoIndex = null
            selectedQuadIndex = null
        }
        _isBending.value = false
        synthController.setPluginControl(
            BenderSymbol.BEND.controlId,
            PortValue.FloatValue(0f),
            ControlEventOrigin.MEDIAPIPE,
        )
    }

    override fun onCleared() {
        super.onCleared()
        handTracker.stop()
    }

    companion object {
        private const val MISS_THRESHOLD = 5
        /** Scale factor for pinch-drag Y delta → 0-1 parameter range. ~20% screen = full range. */
        private const val PARAM_ADJUST_SCALE = 5f
        /** Scale factor for Z-depth delta → envelope speed. Z values are smaller, so scale more aggressively. */
        private const val ENV_SPEED_Z_SCALE = 10f

        fun previewFeature(
            state: MediaPipeUiState = MediaPipeUiState(),
        ): MediaPipeFeature =
            object : MediaPipeFeature {
                override val stateFlow: StateFlow<MediaPipeUiState> = MutableStateFlow(state)
                override val actions: MediaPipePanelActions = MediaPipePanelActions.EMPTY
            }

        @Composable
        fun feature(): MediaPipeFeature =
            synthFeature<MediaPipeViewModel, MediaPipeFeature>()
    }
}
