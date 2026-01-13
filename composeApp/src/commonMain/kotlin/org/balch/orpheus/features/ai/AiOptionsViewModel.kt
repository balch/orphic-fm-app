package org.balch.orpheus.features.ai

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMode
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.PresetsRepository
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.generative.DroneAgentConfig
import org.balch.orpheus.features.ai.generative.SoloAgentConfig
import org.balch.orpheus.features.ai.generative.SynthControlAgent
import org.balch.orpheus.features.ai.tools.ReplExecuteArgs
import org.balch.orpheus.features.ai.tools.ReplExecuteTool
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
    val onSelectModel: (AiModel) -> Unit
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
            onSelectModel = {}
        )
    }
}

typealias AiOptionsFeature = SynthFeature<AiOptionsUiState, AiOptionsPanelActions>

/**
 *ViewModel for the AI Options panel.
 *
 * Manages state for the 4 AI feature buttons:
 * - Drone: AI-generated drone accompaniment
 * - Solo: Standalone AI-driven ambient sound
 * - REPL: Generate Tidal code patterns
 * - Chat: Open chat dialog
 */
@Suppress("UNCHECKED_CAST")
@Inject
@ViewModelKey(AiOptionsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AiOptionsViewModel(
    private val agent: OrpheusAgent,
    private val synthAgentFactory: SynthControlAgent.Factory,
    private val presetsRepository: PresetsRepository,
    private val presetLoader: PresetLoader,
    private val replExecuteTool: ReplExecuteTool,
    private val replCodeEventBus: ReplCodeEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus,
    private val synthEngine: SynthEngine,
    private val aiKeyRepository: AiKeyRepository,
    private val aiModelProvider: AiModelProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val synthOrchestrator: SynthOrchestrator,
    private val mediaSessionStateManager: MediaSessionStateManager,
) : ViewModel(), AiOptionsFeature {

    private val log = logging("AiOptionsViewModel")

    // StateFlows to hold the current agent instances (recreated on each start to reset state)
    private val _droneAgent = MutableStateFlow<SynthControlAgent?>(null)
    private val _soloAgent = MutableStateFlow<SynthControlAgent?>(null)

    init {
        // Observe model changes and restart the OrpheusAgent (Chat) when model changes
        viewModelScope.launch {
            aiModelProvider.selectedModel
                .drop(1) // Skip initial emission to avoid restart on startup
                .collect { model ->
                    log.debug { "Model changed to ${model.displayName}, restarting chat agent" }
                    agent.restart()
                }
        }
        
        // Subscribe to user interaction events to deactivate REPL mode
        viewModelScope.launch {
            replCodeEventBus.events.collect { event ->
                if (event is ReplCodeEvent.UserInteraction && _isReplActive.value) {
                    log.debug { "User interaction detected, deactivating REPL mode" }
                    _isReplActive.value = false
                }
            }
        }
        
        // Subscribe to playback lifecycle events (e.g., foreground service stop)
        viewModelScope.launch {
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
            viewModelScope.launch {
                try {
                    replExecuteTool.execute(ReplExecuteArgs(code = "hush"))
                } catch (e: Exception) {
                    log.warn { "Failed to hush REPL: ${e.message}" }
                }
            }
        }
        
        // Reset playback mode to USER since no AI is active
        synthOrchestrator.setPlaybackMode(PlaybackMode.USER)
    }

    /**
     * Save a user-provided API key.
     */
    private fun saveApiKey(aiProvider: AiProvider, key: String) {
        viewModelScope.launch(dispatcherProvider.io) {
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
        viewModelScope.launch(dispatcherProvider.io) {
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
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
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
            viewModelScope.launch(dispatcherProvider.io) {
                runCatchingSuspend {
                    replExecuteTool.execute(ReplExecuteArgs(code = "hush"))
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
            viewModelScope.launch(dispatcherProvider.io) {
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
                newAgent.start()
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
            viewModelScope.launch {
                try {
                    replExecuteTool.execute(ReplExecuteArgs(code = "hush"))
                } catch (e: Exception) {
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
            viewModelScope.launch(dispatcherProvider.io) {
                // If we just stopped Drone, give it a moment to clean up
                if (wasDroneActive) {
                    delay(500)
                }
                
                val soloPreset = generateRandomSoloPreset()
                
                // Fade in: Apply preset with Quad Volumes = 0, then ramp up to 1.0
                // Master Volume is not affected by presets (user control only)
                val presetWithZeroQuadVol = soloPreset.copy(
                    quadGroupVolumes = listOf(0f, 0f, 0f)
                )
                
                presetLoader.applyPreset(presetWithZeroQuadVol)
                log.debug { "Applied solo preset: ${soloPreset.name} (fading in)" }
                
                // Fade in using JSyn's LinearRamp for sample-accurate, click-free transitions
                val fadeDuration = 2.0f  // seconds
                log.debug { "Fading in quad volumes over ${fadeDuration}s" }
                synthEngine.fadeQuadVolume(0, 1f, fadeDuration)
                synthEngine.fadeQuadVolume(1, 1f, fadeDuration)
                synthEngine.fadeQuadVolume(2, 1f, fadeDuration)
                
                // Start the agent immediately - it generates the sound that fades in
                newAgent.start()
                
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
    private fun generateRandomSoloPreset(): DronePreset {
        val r = Random
        
        // Solo/Lead characteristics:
        // - Fast attack (low envelope speed values)
        // - Rich harmonics (FM modulation)
        // - Specific spatial effects (Ping-pong delay)
        // - Presence (Drive/Distortion)
        
        return DronePreset(
            name = "Solo Session ${Clock.System.now().epochSeconds}",
            
            // Unison with very slight detune for thickness
            voiceTunes = List(12) { 
                0.5f + (r.nextFloat() - 0.5f) * 0.01f 
            },
            
            // Bright timber
            voiceModDepths = List(12) { 0.3f + r.nextFloat() * 0.5f },
            
            // Fast attack/decay for lead lines
            voiceEnvelopeSpeeds = List(12) { r.nextFloat() * 0.2f },
            
            // Sharp waveforms
            pairSharpness = List(6) { 0.5f + r.nextFloat() * 0.5f },
            
            // Mostly FM for metallic/bell/lead tones
            duoModSources = List(6) { 
                if (r.nextFloat() > 0.8) ModSource.LFO else ModSource.VOICE_FM 
            },
            
            // Ping-pong delay style
            delayTime1 = 0.25f, // 1/4 note approx
            delayTime2 = 0.375f, // Dotted 1/4 approx
            delayFeedback = 0.3f + r.nextFloat() * 0.2f,
            delayMix = 0.3f + r.nextFloat() * 0.2f,
            
            // Lead presence
            drive = 0.4f + r.nextFloat() * 0.4f,
            distortionMix = 0.2f + r.nextFloat() * 0.3f, // Some edge
            vibrato = 0.3f + r.nextFloat() * 0.4f, // Expressive vibrato
            voiceCoupling = 0.2f + r.nextFloat() * 0.3f,
            
            // Standard FM structure usually
            fmStructureCrossQuad = false,
            
            // Reset quads (no hold/drone by default)
            quadGroupPitches = List(3) { 0.5f },
            quadGroupHolds = List(3) { 0.0f }
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
            viewModelScope.launch {
                panelExpansionEventBus.expand(PanelId.CODE)
                panelExpansionEventBus.collapse(PanelId.LFO)
                panelExpansionEventBus.collapse(PanelId.DELAY)
                log.debug { "Expanded CODE, collapsed LFO and DELAY panels" }
            }
            
            // Emit generating event immediately so UI shows loading state
            viewModelScope.launch {
                replCodeEventBus.emitGenerating()
            }
            
            // Reset to Default preset
            viewModelScope.launch(dispatcherProvider.io) {
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
            viewModelScope.launch {
                replCodeEventBus.emitUserInteraction()
            }
            
            // Stop all REPL patterns with ramp down
            viewModelScope.launch {
                try {
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
                    
                    replExecuteTool.execute(
                        ReplExecuteArgs(code = "hush")
                    )
                    log.debug { "Hushed REPL patterns" }
                    
                    // Restore volumes after hush (instant, not faded)
                    synthEngine.setQuadVolume(0, vol0)
                    synthEngine.setQuadVolume(1, vol1)
                    synthEngine.setQuadVolume(2, vol2)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn { "Failed to hush REPL patterns gracefully: ${e.message}" }
                    // Fallback
                    replExecuteTool.execute(
                        ReplExecuteArgs(code = "hush")
                    )
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
        viewModelScope.launch {
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
        onSelectModel = ::selectModel
    )

    fun updateDialogPosition(x: Float, y: Float) {
        _dialogPosition.value = x to y
    }

    fun updateDialogSize(width: Float, height: Float) {
        _dialogSize.value = width to height
    }

    override val stateFlow: StateFlow<AiOptionsUiState> = combine(
            listOf(
            _isDroneActive,
            _isSoloActive,
            _isReplActive,
            _showChatDialog,
            _sessionId,
            messages,
            _dialogPosition,
            _dialogSize
            )
        ) { results ->
        AiOptionsUiState(
            isDroneActive = results[0] as Boolean,
            isSoloActive = results[1] as Boolean,
            isReplActive = results[2] as Boolean,
            showChatDialog = results[3] as Boolean,
            sessionId = results[4] as Int,
            messages = results[5] as List<ChatMessage>,
            dialogPosition = results[6] as Pair<Float, Float>,
            dialogSize = results[7] as Pair<Float, Float>,
            availableModels = availableModels,
            aiStatusMessages = aiStatusMessages,
            aiInputLog = aiInputLog,
            aiControlLog = aiControlLog,
            isApiKeySet = aiKeyRepository.isApiKeySet,
            isUserProvidedKey = aiKeyRepository.isUserProvidedKey,
            selectedModel = aiModelProvider.selectedModel.value
        )

    }
        .mapNotNull { it
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiOptionsUiState(
                availableModels = availableModels,
                aiStatusMessages = aiStatusMessages,
                aiInputLog = aiInputLog,
                aiControlLog = aiControlLog
            )
        )
/*
        val (drone, solo, repl, chat) = flags
        val (sessionInfo, modelData) = session
        val (sessionId, apiKeySet, userKey) = sessionInfo
        val (model, msgs) = modelData
        val (pos, size) = layout
        
        AiOptionsUiState(
            isDroneActive = drone,
            isSoloActive = solo,
            isReplActive = repl,
            showChatDialog = chat,
            sessionId = sessionId,
            isApiKeySet = apiKeySet,
            isUserProvidedKey = userKey,
            selectedModel = model,
            availableModels = availableModels,
            messages = msgs,
            dialogPosition = pos,
            dialogSize = size,
            aiStatusMessages = aiStatusMessages,
            aiInputLog = aiInputLog,
            aiControlLog = aiControlLog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiOptionsUiState(
            availableModels = availableModels,
            aiStatusMessages = aiStatusMessages,
            aiInputLog = aiInputLog,
            aiControlLog = aiControlLog
        )

 */

    companion object {
        fun previewFeature(state: AiOptionsUiState = AiOptionsUiState()): AiOptionsFeature =
            object : AiOptionsFeature {
                override val stateFlow: StateFlow<AiOptionsUiState> = MutableStateFlow(state)
                override val actions: AiOptionsPanelActions = AiOptionsPanelActions.EMPTY
            }

        @Composable
        fun feature(): AiOptionsFeature =
            synthViewModel<AiOptionsViewModel, AiOptionsFeature>()
    }
}
