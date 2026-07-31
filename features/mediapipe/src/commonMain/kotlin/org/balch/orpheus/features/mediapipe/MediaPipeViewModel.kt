package org.balch.orpheus.features.mediapipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.ControlStateSnapshot
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.gestures.AslCategory
import org.balch.orpheus.core.gestures.AslEvent
import org.balch.orpheus.core.gestures.AslInteractionEngine
import org.balch.orpheus.core.gestures.AslSign
import org.balch.orpheus.core.gestures.ConductorEvent
import org.balch.orpheus.core.gestures.ConductorInteractionEngine
import org.balch.orpheus.core.gestures.GestureInterpreter
import org.balch.orpheus.core.gestures.GestureMode
import org.balch.orpheus.core.gestures.GestureState
import org.balch.orpheus.core.gestures.InteractionPhase
import org.balch.orpheus.core.gestures.KeyboardEvent
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine
import org.balch.orpheus.core.gestures.SwipeDirection
import org.balch.orpheus.core.mediapipe.CameraFrame
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.mediapipe.TrackedHand
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BenderSymbol
import org.balch.orpheus.core.plugin.symbols.VizSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Panel display mode for the gesture/camera panel.
 * - VIZ: Transparent — background visualization shows through
 * - OFF: Panel disabled (black, no camera or tracking)
 * - CAMERA: Camera feed with hand tracking (when hardware available)
 */
enum class GesturePanelMode { VIZ, OFF, CAMERA }

@Immutable
data class MediaPipeUiState(
    val panelMode: GesturePanelMode = GesturePanelMode.OFF,
    val cameraAvailable: Boolean = false,
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
    val pressedKeys: Set<Int> = emptySet(),
    val keyboardEngineName: String? = null,
)

@Immutable
data class MediaPipePanelActions(
    val toggleEnabled: () -> Unit,
    val toggleHold: (voiceIndex: Int) -> Unit,
    val setPanelMode: (GesturePanelMode) -> Unit,
) {
    companion object {
        val EMPTY = MediaPipePanelActions({}, {}, {})
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
@SynthFeatureKey(MediaPipeFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class MediaPipeViewModel(
    private val handTracker: HandTracker,
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    private val _engine: SynthEngine,
    private val scope: FeatureCoroutineScope,
) : MediaPipeFeature {

    override val engine: SynthEngine get() = _engine

    private val _panelSwipeEvents = MutableSharedFlow<SwipeDirection>(extraBufferCapacity = 4)
    override val panelSwipeEvents: SharedFlow<SwipeDirection> = _panelSwipeEvents

    private val _stringBends = MutableStateFlow(listOf(0f, 0f, 0f, 0f))
    override val stringBends: StateFlow<List<Float>> = _stringBends

    private val log = logging("MediaPipeVM")
    private val gestureInterpreter = GestureInterpreter()
    private val aslEngine = AslInteractionEngine()
    private val conductorEngine = ConductorInteractionEngine()
    private val keyboardEngine = KeyboardInteractionEngine()
    private var savedVoiceTuning: SavedVoiceTuning? = null
    private val _pressedKeys = MutableStateFlow<Set<Int>>(emptySet())
    private val _keyboardEngineName = MutableStateFlow<String?>(null)

    private class SavedVoiceTuning(
        val voiceTunes: FloatArray,
        val quadPitches: FloatArray,
    )

    // Save-on-first-write: captures pre-gesture values so camera-off can restore them
    private val controlSnapshot = ControlStateSnapshot(synthController)
    private val _gestureMode = MutableStateFlow(GestureMode.ASL)
    private var lastModeToggleMs = 0L // cooldown prevents re-toggle bounce
    private val modeToggleCooldownMs = 1500L

    private val _panelMode = MutableStateFlow(GesturePanelMode.OFF)
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
        // Stop hand tracker when the feature scope is cancelled
        scope.onCleared { handTracker.stop() }

        // Track hold state from any source (UI, MediaPipe, AI) for display and toggle logic
        scope.launch {
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
        setPanelMode = { mode ->
            _panelMode.value = mode
            when (mode) {
                GesturePanelMode.CAMERA, GesturePanelMode.VIZ -> {
                    if (!_isEnabled.value && handTracker.isAvailable) toggleTracking()
                }
                GesturePanelMode.OFF -> {
                    if (_isEnabled.value) toggleTracking()
                }
            }
        },
    )

    init {
        // Process gesture events via AslInteractionEngine in a dedicated coroutine
        // that runs for the ViewModel's entire lifetime, independent of UI subscription
        // state. This ensures events (voice gates, parameter adjustments) are never
        // lost during brief UI unsubscriptions (e.g., configuration changes).
        scope.launch(dispatcherProvider.default) {
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
                            if (timestampMs - lastModeToggleMs > modeToggleCooldownMs) {
                                lastModeToggleMs = timestampMs
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
                        if (!conductorEngine.isAnyVoiceGated) {
                            val swipeEvents = aslEngine.checkSwipe(gestures, timestampMs)
                            for (event in swipeEvents) {
                                dispatchAslEvent(event)
                            }
                        } else {
                            // Reset swipe state so stale palmX doesn't cause false trigger on release
                            aslEngine.checkSwipe(emptyList(), timestampMs)
                        }
                    }
                    GestureMode.KEYBOARD -> {
                        // Exit: fist sign (A/S) returns to ASL mode
                        val signerHand = gestures.firstOrNull {
                            it.aslSign != null && it.aslConfidence >= 0.7f
                        }
                        val exitSign = signerHand?.aslSign
                        if (exitSign == AslSign.LETTER_A || exitSign == AslSign.LETTER_S) {
                            if (timestampMs - lastModeToggleMs > modeToggleCooldownMs) {
                                lastModeToggleMs = timestampMs
                                exitKeyboardMode()
                                return@combine
                            }
                        }
                        val events = keyboardEngine.update(gestures, timestampMs)
                        for (event in events) {
                            dispatchKeyboardEvent(event)
                        }
                    }
                }
            }.collect {}
        }
    }

    override val stateFlow: StateFlow<MediaPipeUiState> =
        combine(
            combine(_isEnabled, _panelMode) { e, m -> Pair(e, m) },
            handTracker.results.onStart { emit(null) },
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
            combine(_isBending, _cachedGestures, _pressedKeys, _keyboardEngineName) { b, g, pk, en ->
                GestureUiExtras(b, g, pk, en)
            },
        ) { (enabled, panelMode), result, frame, aslExtras, gestureExtras ->
            if (!enabled || result == null || result.hands.isEmpty()) {
                MediaPipeUiState(
                    panelMode = panelMode,
                    cameraAvailable = handTracker.isAvailable,
                    isEnabled = enabled,
                    isTracking = false,
                    cameraFrame = if (enabled) frame else null,
                    heldVoiceIndices = aslExtras.heldIndices,
                    selectedTarget = aslExtras.selectedTarget,
                    selectedParam = aslExtras.selectedParam,
                    modePrefix = aslExtras.modePrefix,
                    interactionPhase = aslExtras.interactionPhase,
                    gestureMode = aslExtras.gestureMode,
                    remoteAdjustArmed = aslEngine.remoteAdjustArmed,
                    selectedDuoIndex = selectedDuoIndex,
                    selectedQuadIndex = selectedQuadIndex,
                    pressedKeys = gestureExtras.pressedKeys,
                    keyboardEngineName = gestureExtras.keyboardEngineName,
                )
            } else {
                MediaPipeUiState(
                    panelMode = panelMode,
                    cameraAvailable = handTracker.isAvailable,
                    isEnabled = enabled,
                    isTracking = true,
                    hands = result.hands,
                    gestureStates = gestureExtras.cachedGestures,
                    cameraFrame = frame,
                    isBending = gestureExtras.bending,
                    heldVoiceIndices = aslExtras.heldIndices,
                    selectedTarget = aslExtras.selectedTarget,
                    selectedParam = aslExtras.selectedParam,
                    modePrefix = aslExtras.modePrefix,
                    interactionPhase = aslExtras.interactionPhase,
                    gestureMode = aslExtras.gestureMode,
                    remoteAdjustArmed = aslEngine.remoteAdjustArmed,
                    selectedDuoIndex = selectedDuoIndex,
                    selectedQuadIndex = selectedQuadIndex,
                    pressedKeys = gestureExtras.pressedKeys,
                    keyboardEngineName = gestureExtras.keyboardEngineName,
                )
            }
        }
            .flowOn(dispatcherProvider.default)
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
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

    private data class GestureUiExtras(
        val bending: Boolean,
        val cachedGestures: List<GestureState>,
        val pressedKeys: Set<Int>,
        val keyboardEngineName: String?,
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
                gestureSetPluginControl(
                    controlId,
                    PortValue.FloatValue(event.value.coerceIn(0f, 1f)),
                )
            }
            is AslEvent.TargetSelected -> {
                // Voice-level target clears any D/Q sub-selection
                selectedDuoIndex = null
                selectedQuadIndex = null
            }
            is AslEvent.TargetDeselected -> {
                selectedDuoIndex = null
                selectedQuadIndex = null
            }
            is AslEvent.DuoSelected -> {
                selectedDuoIndex = event.duoIndex
                selectedQuadIndex = null
            }
            is AslEvent.QuadSelected -> {
                selectedQuadIndex = event.quadIndex
                selectedDuoIndex = null
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
                if (now - lastModeToggleMs > modeToggleCooldownMs) {
                    lastModeToggleMs = now
                    log.info { "CONDUCTOR enter via ILY" }
                    toggleGestureMode()
                }
            }
            is AslEvent.ToggleKeyboardMode -> {
                @OptIn(ExperimentalTime::class)
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastModeToggleMs > modeToggleCooldownMs) {
                    lastModeToggleMs = now
                    log.info { "KEYBOARD enter via E" }
                    enterKeyboardMode()
                }
            }
        }
    }

    private fun dispatchConductorEvent(event: ConductorEvent) {
        when (event) {
            is ConductorEvent.VoiceGateOn -> {
                synthController.emitPulseStart(event.voiceIndex)
            }
            is ConductorEvent.VoiceGateOff -> {
                synthController.emitPulseEnd(event.voiceIndex)
            }
            is ConductorEvent.VoiceBendSet -> {
                val duoIndex = ConductorInteractionEngine.duoForVoice(event.voiceIndex)
                _engine.setStringBend(duoIndex, event.bendAmount, 0.5f)
                _stringBends.value = _stringBends.value.toMutableList().apply {
                    set(duoIndex, event.bendAmount)
                }
                updateVizKnobsForBend(duoIndex, event.bendAmount)
            }
            is ConductorEvent.VoiceRelease -> {
                val duoIndex = ConductorInteractionEngine.duoForVoice(event.voiceIndex)
                _engine.releaseStringBend(duoIndex)
                _stringBends.value = _stringBends.value.toMutableList().apply {
                    set(duoIndex, 0f)
                }
                resetVizKnobsForBend(duoIndex)
            }
            is ConductorEvent.DuoBendSet -> {
                _engine.setStringBend(event.duoIndex, event.bendAmount, 0.5f)
                _stringBends.value = _stringBends.value.toMutableList().apply {
                    set(event.duoIndex, event.bendAmount)
                }
                updateVizKnobsForBend(event.duoIndex, event.bendAmount)
            }
            is ConductorEvent.DuoRelease -> {
                _engine.releaseStringBend(event.duoIndex)
                _stringBends.value = _stringBends.value.toMutableList().apply {
                    set(event.duoIndex, 0f)
                }
                resetVizKnobsForBend(event.duoIndex)
            }
            is ConductorEvent.BendSet -> {
                synthController.emitBendChange(event.value)
            }
            is ConductorEvent.HoldSet -> {
                gestureSetPluginControl(
                    VoiceSymbol.quadHold(event.quadIndex).controlId,
                    PortValue.FloatValue(event.value),
                )
            }
            is ConductorEvent.DynamicsSet -> {
                gestureSetPluginControl(
                    VoiceSymbol.quadVolume(event.quadIndex).controlId,
                    PortValue.FloatValue(event.value),
                )
            }
            is ConductorEvent.TimbreSet -> { /* no-op, removed */ }
        }
    }

    private fun dispatchKeyboardEvent(event: KeyboardEvent) {
        when (event) {
            is KeyboardEvent.NoteOn -> {
                _pressedKeys.value = _pressedKeys.value + event.keyIndex
                synthController.emitPulseStart(event.keyIndex)
            }
            is KeyboardEvent.NoteOff -> {
                _pressedKeys.value = _pressedKeys.value - event.keyIndex
                synthController.emitPulseEnd(event.keyIndex)
            }
            is KeyboardEvent.CycleEngine -> {
                // TODO: cycle voice engine model for all 12 voices
            }
        }
    }

    private fun enterKeyboardMode() {
        // Snapshot current tunings so we can restore on exit
        savedVoiceTuning = SavedVoiceTuning(
            voiceTunes = FloatArray(KeyboardInteractionEngine.NUM_KEYS) { _engine.getVoiceTune(it) },
            quadPitches = FloatArray(NUM_QUADS) { _engine.getQuadPitch(it) },
        )
        // Set quad pitches to unity (0.5) so keyboard tuning is absolute
        for (q in 0 until NUM_QUADS) _engine.setQuadPitch(q, 0.5f)
        // Tune each voice to a chromatic note starting at middle C (MIDI 60)
        // Formula: freq = 55.0 * 2^(tune * 4.0), so tune = (midiNote - MIDI_TUNE_OFFSET) / MIDI_TUNE_DIVISOR
        for (i in 0 until KeyboardInteractionEngine.NUM_KEYS) {
            val tune = (MIDI_BASE_NOTE + i - MIDI_TUNE_OFFSET).toFloat() / MIDI_TUNE_DIVISOR
            _engine.setVoiceTune(i, tune)
        }
        _gestureMode.value = GestureMode.KEYBOARD
    }

    private fun exitKeyboardMode() {
        // Flush any active notes
        val flush = keyboardEngine.reset()
        for (e in flush) dispatchKeyboardEvent(e)
        _pressedKeys.value = emptySet()
        // Restore saved tunings
        savedVoiceTuning?.let { saved ->
            for (i in saved.voiceTunes.indices) _engine.setVoiceTune(i, saved.voiceTunes[i])
            for (q in saved.quadPitches.indices) _engine.setQuadPitch(q, saved.quadPitches[q])
        }
        savedVoiceTuning = null
        _gestureMode.value = GestureMode.ASL
        _selectedTarget.value = null
        _selectedParam.value = null
        _modePrefix.value = null
        _interactionPhase.value = InteractionPhase.IDLE
        _isBending.value = false
    }

    private fun updateVizKnobsForBend(duoIndex: Int, bendAmount: Float) {
        val vizValue = (bendAmount + 1f) / 2f
        if (duoIndex == 0) {
            synthController.setPluginControl(VizSymbol.KNOB_1.controlId, PortValue.FloatValue(vizValue), ControlEventOrigin.MEDIAPIPE)
        } else if (duoIndex == 3) {
            synthController.setPluginControl(VizSymbol.KNOB_2.controlId, PortValue.FloatValue(vizValue), ControlEventOrigin.MEDIAPIPE)
        }
    }

    private fun resetVizKnobsForBend(duoIndex: Int) {
        if (duoIndex == 0) {
            synthController.setPluginControl(VizSymbol.KNOB_1.controlId, PortValue.FloatValue(0.5f), ControlEventOrigin.MEDIAPIPE)
        } else if (duoIndex == 3) {
            synthController.setPluginControl(VizSymbol.KNOB_2.controlId, PortValue.FloatValue(0.5f), ControlEventOrigin.MEDIAPIPE)
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
            GestureMode.KEYBOARD -> {
                exitKeyboardMode()
                return // exitKeyboardMode already switches mode and resets state
            }
        }
        // Switch mode
        _gestureMode.value = when (_gestureMode.value) {
            GestureMode.ASL -> GestureMode.CONDUCTOR
            GestureMode.CONDUCTOR -> GestureMode.ASL
            GestureMode.KEYBOARD -> GestureMode.ASL
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
        gestureSetPluginControl(controlId, PortValue.FloatValue(newValue))
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
            gestureSetPluginControl(controlId, PortValue.FloatValue(newValue))
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
     * Routing depends on whether D/Q sub-selection is active:
     * - Quad selected (Q+number): H→quadHold, W→quadVolume, B→quadPitch
     * - Duo selected (D+number): M→duoMorph, S→duoSharpness, L→duoModLevel
     * - Voice selected (number alone): B→globalBend, params auto-derive duo/quad from voice
     * - System sign (V, C, Y) → global parameter (param is the target itself)
     */
    private fun resolveControlId(target: AslSign, param: AslSign): PluginControlId? {
        return when {
            target.category == AslCategory.NUMBER -> {
                val vi = target.voiceIndex() ?: return null
                val qi = selectedQuadIndex
                val di = selectedDuoIndex

                when {
                    // Quad sub-selection active: only quad-level params
                    qi != null -> when (param) {
                        AslSign.LETTER_H -> VoiceSymbol.quadHold(qi).controlId
                        AslSign.LETTER_W -> VoiceSymbol.quadVolume(qi).controlId
                        AslSign.LETTER_B -> VoiceSymbol.quadPitch(qi).controlId
                        else -> null
                    }
                    // Duo sub-selection active: only duo-level params
                    di != null -> when (param) {
                        AslSign.LETTER_M -> VoiceSymbol.duoMorph(di).controlId
                        AslSign.LETTER_S -> VoiceSymbol.duoSharpness(di).controlId
                        AslSign.LETTER_L -> VoiceSymbol.duoModSourceLevel(di).controlId
                        else -> null
                    }
                    // Voice-level: auto-derive duo/quad from voice index
                    else -> when (param) {
                        AslSign.LETTER_M -> VoiceSymbol.duoMorph(vi / 2).controlId
                        AslSign.LETTER_S -> VoiceSymbol.duoSharpness(vi / 2).controlId
                        AslSign.LETTER_L -> VoiceSymbol.duoModSourceLevel(vi / 2).controlId
                        AslSign.LETTER_H -> VoiceSymbol.quadHold(vi / 4).controlId
                        AslSign.LETTER_W -> VoiceSymbol.quadVolume(vi / 4).controlId
                        AslSign.LETTER_B -> BenderSymbol.BEND.controlId
                        else -> null
                    }
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
            // Deactivation is handled by the combine flow on dispatcherProvider.default
            // when it sees enabled=false. We must NOT call deactivateGestureControls() here
            // because this runs on the main thread while the combine accesses the engines
            // on the default dispatcher — concurrent access is not thread-safe.
        }
    }

    /** Set a plugin control from gesture tracking, saving the pre-gesture value on first write. */
    private fun gestureSetPluginControl(id: PluginControlId, value: PortValue) {
        controlSnapshot.setPluginControl(id, value, ControlEventOrigin.MEDIAPIPE)
    }

    /** Deactivate gesture controls and reset ASL engine state. */
    @OptIn(ExperimentalTime::class)
    private fun deactivateGestureControls() {
        log.info { "deactivateGestureControls (mode=${_gestureMode.value})" }

        // 1. Restore all plugin controls modified during this gesture session
        //    (hold, dynamics, parameter adjustments, etc.) to pre-gesture values.
        //    Must happen BEFORE gate-off so hold is zeroed when envelopes release.
        controlSnapshot.restore(ControlEventOrigin.MEDIAPIPE)

        // 2. Flush pending events (e.g., gate-off for voices still gated via pinch)
        val timestampMs = Clock.System.now().toEpochMilliseconds()
        val flushEvents = aslEngine.update(emptyList(), timestampMs)
        for (event in flushEvents) {
            dispatchAslEvent(event)
        }
        aslEngine.reset()

        // 3. Flush conductor engine if active
        val conductorFlush = conductorEngine.reset()
        for (event in conductorFlush) {
            dispatchConductorEvent(event)
        }

        // 3b. Flush keyboard engine and restore tunings if in keyboard mode
        if (_gestureMode.value == GestureMode.KEYBOARD) {
            exitKeyboardMode()
        } else {
            val kbFlush = keyboardEngine.reset()
            for (event in kbFlush) dispatchKeyboardEvent(event)
        }

        // 4. Safety net: force all voice gates off directly through the engine.
        //    The SharedFlow path (emitPulseEnd → VoiceViewModel → setVoiceGate) is
        //    indirect and async. Belt-and-suspenders: set gates off on the engine directly.
        for (i in 0 until KeyboardInteractionEngine.NUM_KEYS) {
            _engine.setVoiceGate(i, false)
        }

        // 5. Reset global pitch bend (conductor uses emitBendChange, not plugin controls)
        synthController.emitBendChange(0f)

        // 6. Release any active string bends
        for (duoIndex in 0..3) {
            _engine.releaseStringBend(duoIndex)
        }
        _stringBends.value = listOf(0f, 0f, 0f, 0f)

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
    }

    companion object {
        private const val MISS_THRESHOLD = 5
        /** Scale factor for pinch-drag Y delta → 0-1 parameter range. ~20% screen = full range. */
        private const val PARAM_ADJUST_SCALE = 5f
        /** Scale factor for Z-depth delta → envelope speed. Z values are smaller, so scale more aggressively. */
        private const val ENV_SPEED_Z_SCALE = 10f
        private const val NUM_QUADS = 3
        /** MIDI note number for middle C — the lowest key in keyboard mode. */
        private const val MIDI_BASE_NOTE = 60
        /** Offset in the engine's tune formula: tune = (midiNote - offset) / divisor. */
        private const val MIDI_TUNE_OFFSET = 33
        /** Divisor in the engine's tune formula: freq = 55.0 * 2^(tune * 4.0). */
        private const val MIDI_TUNE_DIVISOR = 48f

        fun previewFeature(
            state: MediaPipeUiState = MediaPipeUiState(),
        ): MediaPipeFeature =
            object : MediaPipeFeature {
                override val stateFlow: StateFlow<MediaPipeUiState> = MutableStateFlow(state)
                override val actions: MediaPipePanelActions = MediaPipePanelActions.EMPTY
            }

        @Composable
        fun feature(): MediaPipeFeature =
            synthFeature<MediaPipeFeature>()
    }
}
