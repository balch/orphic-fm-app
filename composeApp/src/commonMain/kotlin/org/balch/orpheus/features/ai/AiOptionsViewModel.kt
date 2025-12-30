package org.balch.orpheus.features.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.GeminiKeyProvider
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.generative.DroneAgentConfig
import org.balch.orpheus.features.ai.generative.SoloAgentConfig
import org.balch.orpheus.features.ai.generative.SynthControlAgent
import org.balch.orpheus.features.ai.tools.ReplExecuteTool
import org.balch.orpheus.features.presets.PresetsViewModel
import kotlin.random.Random

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
@ViewModelKey(AiOptionsViewModel::class)
@ContributesIntoMap(AppScope::class)
class AiOptionsViewModel(
    private val agent: OrpheusAgent,
    synthAgentFactory: SynthControlAgent.Factory,
    private val presetsViewModel: PresetsViewModel,
    private val replExecuteTool: ReplExecuteTool,
    private val panelExpansionEventBus: PanelExpansionEventBus,
    private val synthEngine: SynthEngine,
    private val geminiKeyProvider: GeminiKeyProvider,
    private val aiModelProvider: AiModelProvider,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

    private val log = logging("AiOptionsViewModel")

    private val droneAgent = synthAgentFactory.create(DroneAgentConfig)
    private val soloAgent = synthAgentFactory.create(SoloAgentConfig)

    /**
     * Combined AI status messages from OrpheusAgent (REPL), DroneAgent, and SoloAgent.
     */
    val aiStatusMessages = merge(
        agent.statusMessages,
        droneAgent.statusMessages,
        soloAgent.statusMessages
    ).shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        replay = 10
    )

    /**
     * Combined AI input logs (prompts sent).
     */
    val aiInputLog = merge(
        droneAgent.inputLog,
        soloAgent.inputLog
    ).shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        replay = 50
    )

    /**
     * Combined AI control logs (actions executed).
     */
    val aiControlLog = merge(
        droneAgent.controlLog,
        soloAgent.controlLog
    ).shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        replay = 50
    )

    // ============================================================
    // AI Feature Toggle States
    // ============================================================

    private val _isDroneActive = MutableStateFlow(false)
    /**
     * Whether the Drone AI accompaniment is active.
     */
    val isDroneActive: StateFlow<Boolean> = _isDroneActive.asStateFlow()

    private val _isSoloActive = MutableStateFlow(false)
    /**
     * Whether the Solo AI mode is active.
     */
    val isSoloActive: StateFlow<Boolean> = _isSoloActive.asStateFlow()

    private val _isReplActive = MutableStateFlow(false)
    /**
     * Whether the REPL AI mode is active.
     */
    val isReplActive: StateFlow<Boolean> = _isReplActive.asStateFlow()

    private val _showChatDialog = MutableStateFlow(false)
    /**
     * Whether the Chat dialog is visible.
     */
    val showChatDialog: StateFlow<Boolean> = _showChatDialog.asStateFlow()

    // ============================================================
    // API Key Management
    // ============================================================

    /**
     * Reactive API key state - true when any key is configured.
     */
    val apiKeyState: StateFlow<Boolean> = geminiKeyProvider.apiKeyState
        .map { !it.isNullOrEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = geminiKeyProvider.isApiKeySet
        )
    
    /**
     * Whether the current key is user-provided (vs build-time).
     */
    val isUserProvidedKey: StateFlow<Boolean> = geminiKeyProvider.isUserProvidedKey

    /**
     * Save a user-provided API key.
     */
    fun saveApiKey(key: String) {
        viewModelScope.launch {
            val success = geminiKeyProvider.saveApiKey(key)
            if (success) {
                log.info { "API key saved successfully" }
            } else {
                log.warn { "Failed to save API key" }
            }
        }
    }

    /**
     * Clear the user-provided API key.
     */
    fun clearApiKey() {
        viewModelScope.launch {
            geminiKeyProvider.clearApiKey()
            log.info { "API key cleared" }
        }
    }

    // ============================================================
    // AI Model Selection
    // ============================================================

    /**
     * Currently selected AI model.
     */
    val selectedModel: StateFlow<AiModel> = aiModelProvider.selectedModel

    /**
     * List of available AI models for dropdown.
     */
    val availableModels: List<AiModel> = aiModelProvider.availableModels

    /**
     * Select a new AI model.
     */
    fun selectModel(model: AiModel) {
        viewModelScope.launch {
            aiModelProvider.selectModel(model)
            log.info { "Model selected: ${model.displayName}" }
        }
    }

    /**
     * Chat messages flow.
     */
    val messages: StateFlow<List<ChatMessage>> = agent.agentFlow
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
    fun toggleDrone() {
        val wasActive = _isDroneActive.value
        
        // If Solo is active, turn it off first? Or allow both? 
        // Let's mutually exclude them for now to avoid chaos, unless requested otherwise.
        if (_isSoloActive.value && !wasActive) {
            toggleSolo()
        }

        _isDroneActive.value = !wasActive

        if (_isDroneActive.value) {
            log.info { "Starting Drone Agent" }
            _showChatDialog.value = true // Ensure ChatDialog is visible
            
            // Generate and apply a unique random preset to jumpstart the creative process
            viewModelScope.launch(dispatcherProvider.io) {
                val uniquePreset = generateRandomDronePreset()
                
                // Fade in: Set volume to 0, apply preset, then ramp up
                val targetVolume = uniquePreset.masterVolume
                synthEngine.setMasterVolume(0f)
                
                presetsViewModel.applyPreset(uniquePreset)
                log.info { "Applied unique drone preset: ${uniquePreset.name}" }
                
                // Ramp volume from 0 to target over 1 second
                synthEngine.setParameterAutomation(
                    controlId = "master_volume",
                    times = floatArrayOf(0f, 1.0f),
                    values = floatArrayOf(0f, targetVolume),
                    count = 2,
                    duration = 1.0f,
                    mode = 0
                )
                
                // Allow ramp to complete before starting agent
                delay(1000)
                synthEngine.clearParameterAutomation("master_volume")
                synthEngine.setMasterVolume(targetVolume)
                
                droneAgent.start()
            }
        } else {
            log.info { "Stopping Drone Agent" }
            droneAgent.stop()
        }
    }
    
    private fun generateRandomDronePreset(): DronePreset {
        val r = Random
        
        return DronePreset(
            name = "Drone Session ${Clock.System.now().epochSeconds}",
            
            // Random tunings favoring harmonics (12 voices)
            voiceTunes = List(12) { 
                if (r.nextBoolean()) 0.5f + r.nextFloat() * 0.02f // Unison with drift
                else 0.5f + (listOf(-0.1f, 0.1f, 0.2f).random()) // Intervals
            },
            
            // Evolving textures
            voiceModDepths = List(12) { r.nextFloat() * 0.4f },
            voiceEnvelopeSpeeds = List(12) { 0.1f + r.nextFloat() * 0.8f },
            
            // Duo configuration (6 pairs)
            pairSharpness = List(6) { r.nextFloat() * 0.5f },
            duoModSources = List(6) { 
                if (r.nextFloat() > 0.7) ModSource.LFO 
                else ModSource.VOICE_FM 
            },
            
            // Spatial
            delayTime1 = 0.2f + r.nextFloat() * 0.4f,
            delayTime2 = 0.3f + r.nextFloat() * 0.4f,
            delayFeedback = 0.4f + r.nextFloat() * 0.4f, // High feedback for drone
            delayMix = 0.4f + r.nextFloat() * 0.3f,
            
            // Global Character
            drive = r.nextFloat() * 0.3f,
            distortionMix = r.nextFloat() * 0.4f,
            vibrato = 0.2f + r.nextFloat() * 0.3f,
            voiceCoupling = r.nextFloat() * 0.4f,
            
            // Quad setup (defaults)
            fmStructureCrossQuad = r.nextBoolean(),
            
            masterVolume = 0.75f
        )
    }

    // ============================================================
    // Solo Actions
    // ============================================================

    /**
     * Toggle the Solo AI on/off.
     */
    fun toggleSolo() {
        val wasActive = _isSoloActive.value

        // Mutually exclusive with Drone
        if (_isDroneActive.value && !wasActive) {
            toggleDrone()
        }

        _isSoloActive.value = !wasActive
        
        if (_isSoloActive.value) {
            log.info { "Starting Solo Agent" }
            _showChatDialog.value = true
            
            // Generate and apply a specialized solo/lead preset
            viewModelScope.launch(dispatcherProvider.io) {
                val soloPreset = generateRandomSoloPreset()
                
                // Fade in: Set volume to 0, apply preset, then ramp up
                val targetVolume = soloPreset.masterVolume
                synthEngine.setMasterVolume(0f)
                
                presetsViewModel.applyPreset(soloPreset)
                log.info { "Applied solo preset: ${soloPreset.name}" }
                
                // Ramp volume from 0 to target over 1 second
                synthEngine.setParameterAutomation(
                    controlId = "master_volume",
                    times = floatArrayOf(0f, 1.0f),
                    values = floatArrayOf(0f, targetVolume),
                    count = 2,
                    duration = 1.0f,
                    mode = 0
                )
                
                // Allow ramp to complete before starting agent
                delay(1000)
                synthEngine.clearParameterAutomation("master_volume")
                synthEngine.setMasterVolume(targetVolume)
                
                soloAgent.start()
            }
        } else {
            log.info { "Stopping Solo Agent" }
            soloAgent.stop()
        }
    }

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
            quadGroupHolds = List(3) { 0.0f },
            
            masterVolume = 0.75f
        )
    }

    // ============================================================
    // Chat Actions
    // ============================================================

    /**
     * Toggle the chat dialog visibility.
     */
    fun toggleChatDialog() {
        _showChatDialog.value = !_showChatDialog.value
        log.info { "Toggled chat dialog: ${_showChatDialog.value}" }
    }

    /**
     * Send a user influence prompt to the Solo agent.
     */
    fun sendSoloInfluence(text: String) {
        log.info { "Sending solo influence: $text" }
        soloAgent.injectUserPrompt(text)
    }

    // ============================================================
    // REPL Actions
    // ============================================================

    /**
     * Toggle REPL generation mode.
     * 
     * When turned ON:
     * - Resets to Default preset
     * - Opens chat dialog
     * - Generates an ambient REPL pattern
     * 
     * When turned OFF:
     * - Stops all REPL patterns with hush
     */
    fun toggleRepl() {
        val wasActive = _isReplActive.value
        _isReplActive.value = !wasActive
        
        if (_isReplActive.value) {
            log.info { "REPL mode activated" }
            
            // Immediately open CODE panel and close LFO/DELAY panels
            // This gives instant UI feedback before AI starts generating
            viewModelScope.launch {
                panelExpansionEventBus.expand(PanelId.CODE)
                panelExpansionEventBus.collapse(PanelId.LFO)
                panelExpansionEventBus.collapse(PanelId.DELAY)
                log.debug { "Expanded CODE, collapsed LFO and DELAY panels" }
            }
            
            // Reset to Default preset
            viewModelScope.launch {
                val presets = presetsViewModel.uiState.value.presets
                val defaultPreset = presets.find { it.name == "Default" }
                if (defaultPreset != null) {
                    presetsViewModel.applyPreset(defaultPreset)
                    log.info { "Reset to Default preset for REPL" }
                }
            }
            
            // Open the chat dialog so user can see the AI working
            _showChatDialog.value = true
            
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
            agent.sendPrompt(PromptIntent(
                prompt = """
                    Create a $selectedMood ambient drone soundscape in ${selectedKey.lowercase()} $selectedMode using repl_execute.
                    
                    Generate a SINGLE repl_execute call with MULTIPLE lines that include:
                    
                    SOUND LAYERS:
                    - d1: Low drone notes based on $selectedKey $selectedMode (e.g., note "${selectedKey.lowercase()}2 ...") 
                    - d2: Mid-range harmony notes
                    - d3: Voice cycling (e.g., slow 2 voices:0 1 2 3)
                    
                    SYNTH CONTROLS (use these Tidal commands in separate d-slots):
                    - d4: drive:0.3 to 0.6 - warm distortion
                    - d5: vibrato:0.3 to 0.5 - gentle LFO modulation
                    - d6: feedback:0.5 to 0.8 - lush delay echoes  
                    - d7: hold:0 0.8 - sustain voice 0
                    
                    Example format:
                    d1 $ slow 2 note "${selectedKey.lowercase()}2 ..."
                    d2 $ note "${selectedKey.lowercase()}3 ..."
                    d3 $ slow 4 voices:0 1 2
                    d4 $ drive:0.4
                    d5 $ vibrato:0.35
                    d6 $ feedback:0.65
                    
                    Make it $selectedMood. After execution, describe the atmosphere in one or two sentences.
                """.trimIndent(),
                displayText = "Generate ambient drone"
            ))
        } else {
            log.info { "REPL mode deactivated" }
            
            // Stop all REPL patterns with ramp down
            viewModelScope.launch {
                try {
                    // Get current volume
                    val currentVol = synthEngine.getMasterVolume()
                    
                    // Ramp down
                    log.debug { "REPL stop: Ramping down volume from $currentVol" }
                    synthEngine.setParameterAutomation(
                        controlId = "master_volume",
                        times = floatArrayOf(0f, 1.0f),
                        values = floatArrayOf(currentVol, 0f),
                        count = 2,
                        duration = 1.0f,
                        mode = 0
                    )
                    
                    delay(1200)
                    
                    replExecuteTool.execute(
                        org.balch.orpheus.features.ai.tools.ReplExecuteTool.Args(
                            code = "hush",
                            showPanel = false
                        )
                    )
                    log.info { "Hushed REPL patterns" }
                    
                    // Restore
                    synthEngine.clearParameterAutomation("master_volume")
                    synthEngine.setMasterVolume(currentVol)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn { "Failed to hush REPL patterns gracefully: ${e.message}" }
                    // Fallback
                    replExecuteTool.execute(
                        org.balch.orpheus.features.ai.tools.ReplExecuteTool.Args(
                            code = "hush",
                            showPanel = false
                        )
                    )
                }
            }
        }
    }

    // ============================================================
    // Dialog State (Hoisted for persistence like "remember where I left it")
    // ============================================================

    private val _dialogPosition = MutableStateFlow(0f to 0f)
    val dialogPosition: StateFlow<Pair<Float, Float>> = _dialogPosition.asStateFlow()

    private val _dialogSize = MutableStateFlow(420f to 550f)
    val dialogSize: StateFlow<Pair<Float, Float>> = _dialogSize.asStateFlow()

    fun updateDialogPosition(x: Float, y: Float) {
        _dialogPosition.value = x to y
    }

    fun updateDialogSize(width: Float, height: Float) {
        _dialogSize.value = width to height
    }
}
