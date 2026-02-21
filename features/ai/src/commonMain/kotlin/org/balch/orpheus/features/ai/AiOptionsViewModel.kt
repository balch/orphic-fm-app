package org.balch.orpheus.features.ai

import androidx.compose.runtime.Composable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.PresetsRepository
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.tidal.ReplCodeEvent
import org.balch.orpheus.core.tidal.ReplCodeEventBus
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.generative.DroneAgentConfig
import org.balch.orpheus.features.ai.generative.SoloAgentConfig
import org.balch.orpheus.features.ai.generative.SynthControlAgent
import org.balch.orpheus.features.ai.tools.ReplExecuteArgs
import org.balch.orpheus.features.ai.tools.ReplExecuteTool
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * UI State for AI Options.
 */
data class AiOptionsUiState(
    val isDroneActive: Boolean = false,
    val isSoloActive: Boolean = false,
    val isReplActive: Boolean = false,
    val showChatDialog: Boolean = false,
    val sessionId: Int = 0,
    val isApiKeySet: Boolean = false,
    val isUserProvidedKey: Boolean = false,
    val selectedModel: AiModel? = null,
    val availableModels: List<AiModel> = emptyList(),
    val dialogPosition: Pair<Float, Float> = 0f to 0f,
    val dialogSize: Pair<Float, Float> = 420f to 550f,
    val messages: List<ChatMessage> = emptyList(),
    // Flows for dashboard
    val aiStatusMessages: Flow<AiStatusMessage> = emptyFlow(),
    val aiInputLog: Flow<AiStatusMessage> = emptyFlow(),
    val aiControlLog: Flow<AiStatusMessage> = emptyFlow()
)

data class AiOptionsPanelActions(
    val onToggleDrone: (Boolean) -> Unit,
    val onToggleSolo: (Boolean) -> Unit,
    val onToggleRepl: (Boolean) -> Unit,
    val onToggleChatDialog: () -> Unit,
    val onSendInfluence: (String) -> Unit,
    val onSaveApiKey: (AiProvider, String) -> Unit,
    val onClearApiKey: (AiProvider) -> Unit,
    val onSelectModel: (AiModel) -> Unit,
    val onDialogPositionChange: (Float, Float) -> Unit,
    val onDialogSizeChange: (Float, Float) -> Unit,
) {
    companion object {
        val EMPTY = AiOptionsPanelActions(
            onToggleDrone = {},
            onToggleSolo = {},
            onToggleRepl = {},
            onToggleChatDialog = {},
            onSendInfluence = {},
            onSaveApiKey = { _, _ -> },
            onClearApiKey = {},
            onSelectModel = {},
            onDialogPositionChange = { _, _ -> },
            onDialogSizeChange = { _, _ -> },
        )
    }
}

interface AiOptionsFeature : SynthFeature<AiOptionsUiState, AiOptionsPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

/**
 *ViewModel for the AI Options panel.
 *
 * Manages state for the 4 AI feature buttons:
 * - Drone: AI-generated drone accompaniment
 * - Solo: Standalone AI-driven ambient sound
 * - REPL: Generate Tidal code patterns
 * - Chat: Open chat dialog
 */
@Inject
@ClassKey(AiOptionsViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class AiOptionsViewModel(
    private val agent: OrpheusAgent,
    private val synthAgentFactory: SynthControlAgent.Factory,
    private val presetsRepository: PresetsRepository,
    private val presetLoader: PresetLoader,
    private val replExecuteTool: ReplExecuteTool,
    private val replCodeEventBus: ReplCodeEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus,
    private val modeChangeEventBus: ModeChangeEventBus,
    private val synthEngine: SynthEngine,
    private val aiKeyRepository: AiKeyRepository,
    private val aiModelProvider: AiModelProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val synthOrchestrator: SynthOrchestrator,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val scope: FeatureCoroutineScope,
) : AiOptionsFeature, AutoCloseable {

    private val log = logging("AiOptionsViewModel")

    // StateFlows to hold the current agent instances (recreated on each start to reset state)
    private val _droneAgent = MutableStateFlow<SynthControlAgent?>(null)
    private val _soloAgent = MutableStateFlow<SynthControlAgent?>(null)

    init {
        // Observe model changes and restart the OrpheusAgent (Chat) when model changes
        scope.launch(dispatcherProvider.default) {
            aiModelProvider.selectedModel
                .drop(1) // Skip initial emission to avoid restart on startup
                .collect { model ->
                    log.debug { "Model changed to ${model.displayName}, restarting chat agent" }
                    agent.restart()
                }
        }

        // Subscribe to user interaction events to deactivate REPL mode
        scope.launch(dispatcherProvider.default) {
            replCodeEventBus.events.collect { event ->
                if (event is ReplCodeEvent.UserInteraction && _isReplActive.value) {
                    log.debug { "User interaction detected, deactivating REPL mode" }
                    _isReplActive.value = false
                }
            }
        }

        // Subscribe to playback lifecycle events (e.g., foreground service stop)
        scope.launch(dispatcherProvider.default) {
            playbackLifecycleManager.events.collect { event ->
                when (event) {
                    is PlaybackLifecycleEvent.StopAll -> {
                        log.debug { "Received StopAll event - stopping all agents" }
                        stopAllAgents()
                    }
                    else -> { /* Ignore other events */ }
                }
            }
        }

        // Subscribe to mode change events (from OrpheusAgent tools like StartCompositionTool)
        scope.launch(dispatcherProvider.default) {
            modeChangeEventBus.events.collect { event ->
                when (event) {
                    is ModeChangeEvent.StartComposition -> {
                        log.debug { "Received StartComposition event: ${event.type} - '${event.userRequest}'" }
                        handleStartComposition(event)
                    }
                    is ModeChangeEvent.StopComposition -> {
                        log.debug { "Received StopComposition event" }
                        stopAllAgents()
                    }
                }
            }
        }
    }
    
    /**
     * Stop all active agents and deactivate REPL mode.
     * Called when playback is stopped (e.g., from foreground service).
     */
    private fun stopAllAgents() {
        // Stop Drone agent if active
        if (_isDroneActive.value) {
            log.debug { "Stopping Drone Agent (lifecycle)" }
            _isDroneActive.value = false
            _droneAgent.value?.stop()
            _droneAgent.value = null
        }
        
        // Stop Solo agent if active
        if (_isSoloActive.value) {
            log.debug { "Stopping Solo Agent (lifecycle)" }
            _isSoloActive.value = false
            _soloAgent.value?.stop()
            _soloAgent.value = null
        }
        
        // Deactivate REPL mode if active
        if (_isReplActive.value) {
            log.debug { "Deactivating REPL mode (lifecycle)" }
            _isReplActive.value = false
            scope.launch(dispatcherProvider.io) {
                runCatchingSuspend {
                    replExecuteTool.tool.execute(ReplExecuteArgs(lines = listOf("hush")))
                }.onFailure { e ->
                    log.warn { "Failed to hush REPL: ${e.message}" }
                }
            }
        }

        // Reset playback mode to USER since no AI is active
        synthOrchestrator.setPlaybackMode(PlaybackMode.USER)
    }

    /**
     * Handle a StartComposition event from the ModeChangeEventBus.
     * This is triggered by the StartCompositionTool used by OrpheusAgent.
     */
    private fun handleStartComposition(event: ModeChangeEvent.StartComposition) {
        // Stop any active agents first
        if (_isDroneActive.value) {
            _isDroneActive.value = false
            mediaSessionStateManager.setDroneActive(false)
            _droneAgent.value?.stop()
            _droneAgent.value = null
        }
        if (_isReplActive.value) {
            _isReplActive.value = false
            scope.launch(dispatcherProvider.io) {
                runCatchingSuspend {
                    replExecuteTool.tool.execute(ReplExecuteArgs(lines = listOf("hush")))
                }
            }
        }

        _isSoloActive.value = true
        mediaSessionStateManager.setSoloActive(true)
        _showChatDialog.value = true  // Show dashboard
        
        log.debug { "Starting Solo Agent with custom params - request: $event" }

        // Update playback mode for notifications
        synthOrchestrator.setPlaybackMode(PlaybackMode.SOLO)
        
        // Clear old agent reference before incrementing session
        _soloAgent.value = null
        _sessionId.value++
        
        // Create fresh agent instance
        val newAgent = synthAgentFactory.create(SoloAgentConfig)
        _soloAgent.value = newAgent
        
        scope.launch(dispatcherProvider.io) {

/*
            if (event.type != CompositionType.USER_PROMPTED) {
                val soloPreset = generateRandomSoloPreset()

                // Fade in: Apply preset with Quad Volumes = 0, then ramp up
                val presetWithZeroQuadVol = soloPreset.copy(
                    quadGroupVolumes = listOf(0f, 0f, 0f)
                )

                presetLoader.applyPreset(presetWithZeroQuadVol)
                log.debug { "Applied solo preset: ${soloPreset.name} (fading in)" }
            }

 */

            // Fade in using JSyn's LinearRamp
            val fadeDuration = 2.0f
            synthEngine.fadeQuadVolume(0, 1f, fadeDuration)
            synthEngine.fadeQuadVolume(1, 1f, fadeDuration)
            synthEngine.fadeQuadVolume(2, 1f, fadeDuration)


            // Start the agent with custom parameters
            newAgent.start(
                compositionType = event.type,
                customPrompt = event.customPrompt,
                moodName = event.moodName,
                userRequest = event.userRequest
            )
            
            // Subscribe to completion signal
            newAgent.completed.collect {
                log.debug { "Solo composition completed - auto-stopping" }
                withContext(dispatcherProvider.main) {
                    onSoloCompleted()
                }
            }
        }
    }

    /**
     * Save a user-provided API key.
     */
    private fun saveApiKey(aiProvider: AiProvider, key: String) {
        scope.launch(dispatcherProvider.io) {
            val success = aiKeyRepository.setKey(aiProvider, key)
            if (success) {
                log.debug { "API key saved successfully" }
            } else {
                log.warn { "Failed to save API key" }
            }
        }
    }

    /**
     * Clear the user-provided API key.
     */
    private fun clearApiKey(aiProvider: AiProvider) {
        scope.launch(dispatcherProvider.io) {
            aiKeyRepository.clearApiKey(aiProvider)
            log.debug { "API key cleared" }
        }
    }

    // ============================================================
    // AI Model Selection
    // ============================================================

    /**
     * Currently selected AI model.
     */
    private val selectedModel: StateFlow<AiModel> = aiModelProvider.selectedModel

    /**
     * List of available AI models for dropdown.
     */
    private val availableModels: List<AiModel> = aiModelProvider.availableModels

    /**
     * Chat messages flow.
     */
    private val messages: StateFlow<List<ChatMessage>> = agent.agentFlow
        .map { it.messages }
        .stateIn(
            scope = scope,
            started = this.sharingStrategy,
            initialValue = emptyList()
        )

    // ============================================================
    // Drone Agent Actions
    // ============================================================

    /**
     * Toggle the Drone AI on/off.
     */
    private fun toggleDrone(showDialog: Boolean = true) {
        val wasActive = _isDroneActive.value
        
        // If Solo is active, turn it off first
        // We need to properly stop and clear the solo agent before starting drone
        val wasSoloActive = _isSoloActive.value
        if (wasSoloActive && !wasActive) {
            // Stop solo synchronously (just the state, not the full coroutine stop)
            _isSoloActive.value = false
            mediaSessionStateManager.setSoloActive(false)
            _soloAgent.value?.stop()
            _soloAgent.value = null  // Clear the reference so its flows stop
        }
        
        // If REPL is active, turn it off first
        val wasReplActive = _isReplActive.value
        if (wasReplActive && !wasActive) {
            _isReplActive.value = false
            scope.launch(dispatcherProvider.io) {
                runCatchingSuspend {
                    replExecuteTool.tool.execute(ReplExecuteArgs(lines = listOf("hush")))
                }.onFailure { e ->
                    log.warn { "Failed to hush REPL: ${e.message}" }
                }
            }
        }

        _isDroneActive.value = !wasActive
        mediaSessionStateManager.setDroneActive(_isDroneActive.value)

        if (_isDroneActive.value) {
            log.debug { "Starting Drone Agent" }
            if (showDialog) {
                _showChatDialog.value = true // Ensure ChatDialog is visible
            }
            
            // Update playback mode for notifications
            synthOrchestrator.setPlaybackMode(PlaybackMode.DRONE)
            
            // Clear old drone agent reference (if any) before incrementing session
            _droneAgent.value = null
            _sessionId.value++ // Signal UI to clear logs
            
            // Create fresh agent instance
            val newAgent = synthAgentFactory.create(DroneAgentConfig)
            _droneAgent.value = newAgent
            
            // Drone mode only uses Quad 3 (voices 8-11) for background drones
            // Quads 1 and 2 remain untouched for user to play over
            scope.launch(dispatcherProvider.io) {
                // If we just stopped Solo, give it a moment to clean up
                if (wasSoloActive) {
                    delay(500)
                }
                
                // Set Quad 3 volume to 0 before starting, then fade in
                synthEngine.setQuadVolume(2, 0f)
                
                // Fade in only Quad 3 using JSyn's LinearRamp
                val fadeDuration = 2.0f  // seconds
                log.debug { "Fading in Quad 3 for drone over ${fadeDuration}s" }
                synthEngine.fadeQuadVolume(2, 1f, fadeDuration)
                
                // Start the agent immediately - it generates the drone sound that fades in
                newAgent.start(CompositionType.DRONE)
            }
        } else {
            log.debug { "Stopping Drone Agent" }
            _droneAgent.value?.stop()
            _droneAgent.value = null  // Clear the reference
            
            // Reset playback mode to USER
            synthOrchestrator.setPlaybackMode(PlaybackMode.USER)
        }
    }

    // ============================================================
    // Solo Actions
    // ============================================================

    /**
     * Toggle the Solo AI on/off.
     */
    private fun toggleSolo(showDialog: Boolean = true) {
        val wasActive = _isSoloActive.value
        log.debug { "toggleSolo called. Was active: $wasActive" }

        // If Drone is active, turn it off first
        // We need to properly stop and clear the drone agent before starting solo
        val wasDroneActive = _isDroneActive.value
        if (wasDroneActive && !wasActive) {
            // Stop drone synchronously (just the state, not the full coroutine stop)
            _isDroneActive.value = false
            mediaSessionStateManager.setDroneActive(false)
            _droneAgent.value?.stop()
            _droneAgent.value = null  // Clear the reference so its flows stop
        }
        
        // If REPL is active, turn it off first
        val wasReplActive = _isReplActive.value
        if (wasReplActive && !wasActive) {
            _isReplActive.value = false
            scope.launch(dispatcherProvider.io) {
                runCatchingSuspend {
                    replExecuteTool.tool.execute(ReplExecuteArgs(lines = listOf("hush")))
                }.onFailure { e ->
                    log.warn { "Failed to hush REPL: ${e.message}" }
                }
            }
        }

        _isSoloActive.value = !wasActive
        mediaSessionStateManager.setSoloActive(_isSoloActive.value)
        
        if (_isSoloActive.value) {
            log.debug { "Starting Solo Agent" }
            if (showDialog) {
                _showChatDialog.value = true
            }
            
            // Update playback mode for notifications
            synthOrchestrator.setPlaybackMode(PlaybackMode.SOLO)
            
            // Clear old solo agent reference (if any) before incrementing session
            _soloAgent.value = null
            _sessionId.value++ // Signal UI to clear logs
            
            // Create fresh agent instance
            val newAgent = synthAgentFactory.create(SoloAgentConfig)
            _soloAgent.value = newAgent
            
            // Generate and apply a specialized solo/lead preset
            scope.launch(dispatcherProvider.io) {
                // If we just stopped Drone, give it a moment to clean up
                if (wasDroneActive) {
                    delay(500)
                }
                
                val soloPreset = generateRandomSoloPreset()
                
                // Fade in: Apply preset with Quad Volumes = 0, then ramp up to 1.0
                // Master Volume is not affected by presets (user control only)
                val presetWithZeroQuadVol = soloPreset.let { p ->
                    val newMap = p.portValues.toMutableMap()
                    newMap["org.balch.orpheus.plugins.voice:quad_volume_0"] = PortValue.FloatValue(0f)
                    newMap["org.balch.orpheus.plugins.voice:quad_volume_1"] = PortValue.FloatValue(0f)
                    newMap["org.balch.orpheus.plugins.voice:quad_volume_2"] = PortValue.FloatValue(0f)
                    p.copy(portValues = newMap)
                }
                
                presetLoader.applyPreset(presetWithZeroQuadVol)
                log.debug { "Applied solo preset: ${soloPreset.name} (fading in)" }
                
                // Fade in using JSyn's LinearRamp for sample-accurate, click-free transitions
                val fadeDuration = 2.0f  // seconds
                log.debug { "Fading in quad volumes over ${fadeDuration}s" }
                synthEngine.fadeQuadVolume(0, 1f, fadeDuration)
                synthEngine.fadeQuadVolume(1, 1f, fadeDuration)
                synthEngine.fadeQuadVolume(2, 1f, fadeDuration)
                
                // Start the agent immediately - it generates the sound that fades in
                newAgent.start(CompositionType.SOLO)
                
                // Subscribe to completion signal - auto-stop Solo when all evolution prompts are done
                newAgent.completed.collect {
                    log.debug { "Solo composition completed - auto-stopping" }
                    // Fade out and turn off Solo mode (on main thread)
                    withContext(dispatcherProvider.main) {
                        onSoloCompleted()
                    }
                }
            }
        } else {
            log.debug { "Stopping Solo Agent" }
            _soloAgent.value?.stop()
            _soloAgent.value = null  // Clear the reference
            
            // Reset playback mode to USER
            synthOrchestrator.setPlaybackMode(PlaybackMode.USER)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun generateRandomSoloPreset(): SynthPreset {
        val r = Random
        
        // Solo/Lead characteristics:
        // - Fast attack (low envelope speed values)
        // - Rich harmonics (FM modulation)
        // - Specific spatial effects (Ping-pong delay)
        // - Presence (Drive/Distortion)
        
        return SynthPreset(
            name = "Solo Session ${Clock.System.now().toEpochMilliseconds() / 1000}",
            portValues = buildMap {
                 // Unison with very slight detune for thickness
                 val tunes = List(12) { 0.5f + (r.nextFloat() - 0.5f) * 0.01f }
                 tunes.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:tune_$i", PortValue.FloatValue(v)) }

                 // Bright timber
                 val modDepths = List(12) { 0.3f + r.nextFloat() * 0.5f }
                 modDepths.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:mod_depth_$i", PortValue.FloatValue(v)) }

                 // Fast attack/decay for lead lines
                 val envSpeeds = List(12) { r.nextFloat() * 0.2f }
                 envSpeeds.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:env_speed_$i", PortValue.FloatValue(v)) }

                 // Sharp waveforms
                 val sharpness = List(6) { 0.5f + r.nextFloat() * 0.5f }
                 sharpness.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:duo_sharpness_$i", PortValue.FloatValue(v)) }

                 // Mostly FM for metallic/bell/lead tones
                 val modSources = List(6) { if (r.nextFloat() > 0.8) ModSource.LFO else ModSource.VOICE_FM }
                 modSources.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:duo_mod_source_$i", PortValue.IntValue(v.ordinal)) }
                 
                 // Ping-pong delay style
                 val delayUri = DelayPlugin.URI
                 put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.25f))
                 put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.375f))
                 put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.3f + r.nextFloat() * 0.2f))
                 put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.3f + r.nextFloat() * 0.2f))
                 put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0f)) // Defaults
                 put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0f)) // Defaults
                 put("$delayUri:mod_source_is_lfo", PortValue.BoolValue(true))
                 put("$delayUri:lfo_wave_is_triangle", PortValue.BoolValue(true))
                 
                 // Lead presence
                 val distUri = DistortionPlugin.URI
                 put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.4f + r.nextFloat() * 0.4f))
                 put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.2f + r.nextFloat() * 0.3f))
                 
                 put("org.balch.orpheus.plugins.voice:vibrato", PortValue.FloatValue(0.3f + r.nextFloat() * 0.4f))
                 put("org.balch.orpheus.plugins.voice:coupling", PortValue.FloatValue(0.2f + r.nextFloat() * 0.3f))
                 
                 // Standard FM structure usually
                 put("org.balch.orpheus.plugins.voice:fm_structure_cross_quad", PortValue.BoolValue(false))
                 put("org.balch.orpheus.plugins.voice:total_feedback", PortValue.FloatValue(0.0f))

                 // Reset quads (no hold/drone by default)
                 List(3) { 0.5f }.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:quad_pitch_$i", PortValue.FloatValue(v)) }
                 List(3) { 0.0f }.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:quad_hold_$i", PortValue.FloatValue(v)) }
                 
                 // Defaults for params not explicitly randomized but needed for fullness
                 val lfoUri = DuoLfoPlugin.URI
                 put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.0f))
                 put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.0f))
                 put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OFF.ordinal))
                 put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))
            },
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
    }

    /**
     * Called when Solo mode completes all evolution prompts.
     * Fades out the sound and turns off Solo mode.
     */
    private fun onSoloCompleted() {
        if (!_isSoloActive.value) return // Already stopped
        
        log.debug { "Solo composition completed - fading out and stopping" }
        
        // Stop the agent first (this will handle the fade out)
        _soloAgent.value?.stop()
        _soloAgent.value = null
        
        // Update state
        _isSoloActive.value = false
        mediaSessionStateManager.setSoloActive(false)
        synthOrchestrator.setPlaybackMode(PlaybackMode.USER)
    }

    // ============================================================
    // Chat Actions
    // ============================================================

    /**
     * Toggle the chat dialog visibility.
     */
    private fun toggleChatDialog() {
        _showChatDialog.value = !_showChatDialog.value
        log.debug { "Toggled chat dialog: ${_showChatDialog.value}" }
    }

    /**
     * Send a user influence prompt to the active agent (Drone or Solo).
     */
    private fun sendInfluence(text: String) {
        log.debug { "Sending influence prompt: $text" }
        _droneAgent.value?.injectUserPrompt(text)
        _soloAgent.value?.injectUserPrompt(text)
    }

    // ============================================================
    // REPL Actions
    // ============================================================

    /**
     * Toggle REPL generation mode.
     * 
     * When turned ON:
     * - Resets to Default preset
     * - Opens chat dialog (optional)
     * - Generates an ambient REPL pattern
     * 
     * When turned OFF:
     * - Stops all REPL patterns with hush
     */
    private fun toggleRepl(showDialog: Boolean = true) {
        val wasActive = _isReplActive.value
        _isReplActive.value = !wasActive
        
        if (_isReplActive.value) {
            log.debug { "REPL mode activated" }
            
            // Immediately open CODE panel and close LFO/DELAY panels
            // This gives instant UI feedback before AI starts generating
            scope.launch(dispatcherProvider.default) {
                panelExpansionEventBus.expand(PanelId.CODE)
                panelExpansionEventBus.collapse(PanelId.LFO)
                panelExpansionEventBus.collapse(PanelId.DELAY)
                log.debug { "Expanded CODE, collapsed LFO and DELAY panels" }
            }

            // Emit generating event immediately so UI shows loading state
            scope.launch(dispatcherProvider.default) {
                replCodeEventBus.emitGenerating()
            }
            
            // Reset to Default preset
            scope.launch(dispatcherProvider.io) {
                val defaultPreset = presetsRepository.getDefault()
                presetLoader.applyPreset(defaultPreset)
                log.debug { "Reset to Default preset for REPL" }
            }
            
            // Open the chat dialog so user can see the AI working
            if (showDialog) {
                _showChatDialog.value = true
            }
            
            // Generate varied prompts by randomizing style elements
            val moods = listOf(
                "ethereal and floating",
                "dark and mysterious",
                "warm and enveloping",
                "cosmic and expansive",
                "meditative and peaceful",
                "hypnotic and pulsing"
            )
            val keys = listOf("C", "D", "E", "F", "G", "A", "B")
            val modes = listOf("minor", "major", "dorian", "phrygian")
            
            val selectedMood = moods.random()
            val selectedKey = keys.random()
            val selectedMode = modes.random()
            
            // Send a specialized prompt for ambient pattern generation with variety
            agent.sendReplPrompt(
                displayText = "Generating Tidal Code",
                selectedMood = selectedMood,
                selectedMode = selectedMode,
                selectedKey = selectedKey
            )
        } else {
            log.debug { "REPL mode deactivated" }

            // Emit UserInteraction to clear the AI generating state
            scope.launch(dispatcherProvider.default) {
                replCodeEventBus.emitUserInteraction()
            }

            // Stop all REPL patterns with ramp down
            scope.launch(dispatcherProvider.io) {
                runCatchingSuspend {
                    // Get current volumes
                    val vol0 = synthEngine.getQuadVolume(0)
                    val vol1 = synthEngine.getQuadVolume(1)
                    val vol2 = synthEngine.getQuadVolume(2)

                    // Fade out using JSyn's LinearRamp for sample-accurate, click-free transitions
                    val fadeDuration = 2.0f  // seconds
                    log.debug { "REPL stop: Fading out quad volumes over ${fadeDuration}s" }
                    synthEngine.fadeQuadVolume(0, 0f, fadeDuration)
                    synthEngine.fadeQuadVolume(1, 0f, fadeDuration)
                    synthEngine.fadeQuadVolume(2, 0f, fadeDuration)

                    // Wait for fade to complete
                    delay((fadeDuration * 1000).toLong())

                    replExecuteTool.tool.execute(
                        ReplExecuteArgs(lines = listOf("hush"))
                    )
                    log.debug { "Hushed REPL patterns" }

                    // Restore volumes after hush (instant, not faded)
                    synthEngine.setQuadVolume(0, vol0)
                    synthEngine.setQuadVolume(1, vol1)
                    synthEngine.setQuadVolume(2, vol2)
                }.onFailure { e ->
                    log.warn { "Failed to hush REPL patterns gracefully: ${e.message}" }
                    // Fallback
                    runCatchingSuspend {
                        replExecuteTool.tool.execute(
                            ReplExecuteArgs(lines = listOf("hush"))
                        )
                    }
                }
            }
        }
    }

    // ============================================================
    // Dialog State (Hoisted for persistence like "remember where I left it")
    // ============================================================

    private val _sessionId = MutableStateFlow(0)

    /**
     * Combined AI status messages fro OrpheusAgent (REPL), DroneAgent, and SoloAgent.
     * Uses flatMapLatest to ensure we subscribe to the *current* agent instance's logs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val aiStatusMessages: Flow<AiStatusMessage> = merge(
        agent.statusMessages,
        _droneAgent.flatMapLatest { it?.statusMessages ?: flow {} },
        _soloAgent.flatMapLatest { it?.statusMessages ?: flow {} }
    )

    /**
     * Combined AI input logs (prompts sent).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val aiInputLog: Flow<AiStatusMessage> = merge(
        _droneAgent.flatMapLatest { it?.inputLog ?: flow {} },
        _soloAgent.flatMapLatest { it?.inputLog ?: flow {} }
    )

    /**
     * Combined AI control logs (actions executed).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val aiControlLog: Flow<AiStatusMessage> = merge(
        _droneAgent.flatMapLatest { it?.controlLog ?: flow {} },
        _soloAgent.flatMapLatest { it?.controlLog ?: flow {} }
    )

    // ============================================================
    // AI Feature Toggle States
    // ============================================================

    private val _isDroneActive = MutableStateFlow(false)
    private val _isSoloActive = MutableStateFlow(false)
    private val _isReplActive = MutableStateFlow(false)
    private val _showChatDialog = MutableStateFlow(false)

    // ============================================================
    // API Key Management
    // ============================================================

    private val _dialogPosition = MutableStateFlow(0f to 0f)
    private val _dialogSize = MutableStateFlow(420f to 550f)

    /**
     * Select a new AI model.
     */
    private fun selectModel(model: AiModel) {
        scope.launch(dispatcherProvider.default) {
            aiModelProvider.selectModel(model)
            log.debug { "Model selected: ${model.displayName}" }
        }
    }

    override val actions = AiOptionsPanelActions(
        onToggleDrone = { toggleDrone(it) },
        onToggleSolo = { toggleSolo(it) },
        onToggleRepl = { toggleRepl(it) },
        onToggleChatDialog = ::toggleChatDialog,
        onSendInfluence = ::sendInfluence,
        onSaveApiKey = ::saveApiKey,
        onClearApiKey = ::clearApiKey,
        onSelectModel = ::selectModel,
        onDialogPositionChange = { x, y -> _dialogPosition.value = x to y },
        onDialogSizeChange = { w, h -> _dialogSize.value = w to h },
    )

    private data class AiFeatureFlags(
        val isDroneActive: Boolean,
        val isSoloActive: Boolean,
        val isReplActive: Boolean,
        val showChatDialog: Boolean
    )

    private data class AiDialogState(
        val sessionId: Int,
        val messages: List<ChatMessage>,
        val dialogPosition: Pair<Float, Float>,
        val dialogSize: Pair<Float, Float>
    )

    override val stateFlow: StateFlow<AiOptionsUiState> = combine(
        combine(_isDroneActive, _isSoloActive, _isReplActive, _showChatDialog, ::AiFeatureFlags),
        combine(_sessionId, messages, _dialogPosition, _dialogSize, ::AiDialogState),
        aiKeyRepository.isApiKeySetFlow,
        aiKeyRepository.isUserProvidedKeyFlow,
        aiModelProvider.selectedModel
    ) { flags, dialog, isKeySet, isUserKey, model ->
        AiOptionsUiState(
            isDroneActive = flags.isDroneActive,
            isSoloActive = flags.isSoloActive,
            isReplActive = flags.isReplActive,
            showChatDialog = flags.showChatDialog,
            sessionId = dialog.sessionId,
            messages = dialog.messages,
            dialogPosition = dialog.dialogPosition,
            dialogSize = dialog.dialogSize,
            availableModels = availableModels,
            aiStatusMessages = aiStatusMessages,
            aiInputLog = aiInputLog,
            aiControlLog = aiControlLog,
            isApiKeySet = isKeySet,
            isUserProvidedKey = isUserKey,
            selectedModel = model
        )
    }.stateIn(
        scope = scope,
        started = this.sharingStrategy,
        initialValue = AiOptionsUiState(
            availableModels = availableModels,
            aiStatusMessages = aiStatusMessages,
            aiInputLog = aiInputLog,
            aiControlLog = aiControlLog
        )
    )

    override fun close() {
        stopAllAgents()
    }

    companion object {
        fun previewFeature(state: AiOptionsUiState = AiOptionsUiState()): AiOptionsFeature =
            object : AiOptionsFeature {
                override val stateFlow: StateFlow<AiOptionsUiState> = MutableStateFlow(state)
                override val actions: AiOptionsPanelActions = AiOptionsPanelActions.EMPTY
            }

        @Composable
        fun feature(): AiOptionsFeature =
            synthFeature<AiOptionsViewModel, AiOptionsFeature>()
    }
}
