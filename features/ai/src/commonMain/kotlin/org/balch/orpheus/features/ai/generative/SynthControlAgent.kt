package org.balch.orpheus.features.ai.generative

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.streaming.StreamFrame
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.ai.currentKoogModel
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.features.ai.CompositionType
import org.balch.orpheus.features.ai.tools.ReplExecuteArgs
import org.balch.orpheus.features.ai.tools.ReplExecuteTool
import org.balch.orpheus.features.ai.tools.SynthControlArgs
import org.balch.orpheus.features.ai.tools.SynthControlTool
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Helper for formatting floats
 */
private fun Float.format(digits: Int): String {
    val s = toString()
    val i = s.indexOf('.')
    return if (i >= 0 && i + digits + 1 < s.length) s.take(i + digits + 1) else s
}

sealed interface SynthAgentState {
    data object Idle : SynthAgentState
    data object Starting : SynthAgentState
    data object Processing : SynthAgentState
    data class Playing(val description: String) : SynthAgentState
    data class Error(val message: String) : SynthAgentState
}

/**
 * State of the Synth Control Agent.
 */

/**
 * Generic AI Agent that controls the synth based on a configuration.
 * Can be used for "Drone" mode, "Solo" mode, etc.
 *
 * Use [SynthControlAgent.Factory] to create instances.
 */
@OptIn(ExperimentalTime::class)
class SynthControlAgent(
    private val config: SynthControlAgentConfig,
    private val aiKeyRepository: AiKeyRepository,
    private val aiModelProvider: AiModelProvider,
    private val synthControlTool: SynthControlTool,
    private val replExecuteTool: ReplExecuteTool,
    private val synthEngine: SynthEngine,
    private val synthController: SynthController,
) {
    private val log = logging(config.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SynthAgentState>(SynthAgentState.Idle)
    val state: StateFlow<SynthAgentState> = _state.asStateFlow()

    // Status messages for UI carousel (Mood/Status)
    private val _statusMessages =
        MutableSharedFlow<AiStatusMessage>(replay = 10, extraBufferCapacity = 10)
    val statusMessages: SharedFlow<AiStatusMessage> = _statusMessages.asSharedFlow()

    // Log of user inputs sent to LLM
    private val _inputLog =
        MutableSharedFlow<AiStatusMessage>(replay = 50, extraBufferCapacity = 50)
    val inputLog: SharedFlow<AiStatusMessage> = _inputLog.asSharedFlow()

    // Log of synth controls executed
    private val _controlLog =
        MutableSharedFlow<AiStatusMessage>(replay = 50, extraBufferCapacity = 50)
    val controlLog: SharedFlow<AiStatusMessage> = _controlLog.asSharedFlow()

    private var agentJob: Job? = null

    // Status tracking
    private var isBusy = false
    private var lastActionTime: Instant = Instant.DISTANT_PAST
    private var lastAgentActionTime: Instant = Instant.DISTANT_PAST
    
    // User influence prompts (for Drone/Solo modes)
    private val _userPrompts = MutableSharedFlow<String>(extraBufferCapacity = 5)

    // Completion signal (emits when all evolution prompts are finished)
    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the agent has completed all evolution prompts (Solo mode) */
    val completed: SharedFlow<Unit> = _completed.asSharedFlow()

    /**
     * Inject a user prompt into the agent's input stream.
     * Used by Drone/Solo modes to let users influence the composition.
     */
    fun injectUserPrompt(text: String) {
        log.debug { "User influence: $text" }
        _userPrompts.tryEmit(text)
    }

    private fun emitStatus(text: String, isLoading: Boolean = false, isError: Boolean = false) {
        _statusMessages.tryEmit(AiStatusMessage(text, isLoading, isError))
    }
    
    private fun emitInput(text: String) {
        _inputLog.tryEmit(AiStatusMessage(text))
    }
    
    private fun emitControl(text: String, isError: Boolean = false) {
        _controlLog.tryEmit(AiStatusMessage(text, isError = isError))
    }
    
    private suspend fun processAction(action: AgentAction) {
        lastAgentActionTime = Clock.System.now()
        when (action.type) {
            ActionType.CONTROL -> {
                val id = action.details.getOrNull(0) ?: return
                val valueStr = action.details.getOrNull(1) ?: return
                val value = valueStr.toFloatOrNull() ?: return
                
                // Block Master Volume changes from AI
                if (id == "stereo_master_vol") {
                    log.warn { "Blocked stereo_master_vol change from AI" }
                    return
                }

                // Enforce active quad restriction
                if (!isControlAllowedForActiveQuads(id)) {
                    log.warn { "Blocked $id - outside active quads ${config.activeQuads}" }
                    return
                }

                log.debug { "Executing CONTROL: $id = $value" }
                emitControl("Set $id: ${value.format(2)}")
                val result = synthControlTool.execute(SynthControlArgs(id, value))
                if (!result.success) {
                    emitControl("Failed: ${result.message}", isError = true)
                    injectUserPrompt("CONTROL action failed for $id: ${result.message}")
                }
            }
            ActionType.REPL -> {
                val code = action.details.getOrNull(0) ?: return
                
                // Block hush commands - the agent should maintain continuous sound
                // User requirement: "filter the hush tag if a song is playing"
                if (code.trim().equals("hush", ignoreCase = true) || code.contains("hush", ignoreCase = true)) {
                    log.warn { "Blocked 'hush' command from AI - continuous playback required" }
                    emitControl("Blocked explicit silence (hush)", isError = false)
                    return
                }

                // Block REPL bpm command - it resets the scheduler and kills sound.
                // AI should use beats_bpm synth control instead.
                if (code.lines().any { line ->
                        val t = line.trim().lowercase()
                        t.startsWith("bpm ") || t.startsWith("bpm:")
                    }) {
                    log.warn { "Blocked REPL 'bpm' command from AI - use beats_bpm synth control instead" }
                    emitControl("Blocked REPL bpm (use beats_bpm control)", isError = false)
                    injectUserPrompt("REPL 'bpm' command is blocked during compositions. Use synth_control with beats_bpm instead (value = desired BPM, e.g. 120.0).")
                    return
                }

                log.debug { "Executing REPL: $code" }
                emitControl("Pattern: $code")
                val result = replExecuteTool.execute(ReplExecuteArgs(lines = code.lines()))
                if (!result.success) {
                    emitControl("Failed: ${result.message}", isError = true)
                    // Provide feedback to the AI so it can correct the code immediately
                    injectUserPrompt("REPL execution failed: ${result.message}. Please fix the code format (e.g. use f# instead of fs) and try again.")
                }
            }
            ActionType.STATUS -> {
                val msg = action.details.getOrNull(0) ?: return
                log.debug { "STATUS: $msg" }
                _state.value = SynthAgentState.Playing(msg)
                emitStatus(msg)
            }
            ActionType.UNKNOWN -> {
                // Ignore
            }
        }
    }

    /**
     * Check if a control ID is allowed given the config's activeQuads restriction.
     * Global/effect controls are always allowed. Voice/duo/quad controls are
     * only allowed if they belong to one of the active quads.
     */
    private fun isControlAllowedForActiveQuads(controlId: String): Boolean {
        // If all quads are active, everything is allowed
        if (config.activeQuads.size >= 3) return true

        val quadIndex = controlIdToQuadIndex(controlId)
            ?: return true // Global/effect controls always allowed
        return quadIndex in config.activeQuads
    }

    /**
     * Map a short-key control name to its quad index (0-based), or null for global controls.
     */
    private fun controlIdToQuadIndex(id: String): Int? {
        // Quad controls: voice_quad_{pitch|hold|volume}_N (0-indexed)
        val quadMatch = Regex("voice_quad_(?:pitch|hold|volume)_(\\d+)").find(id)
        if (quadMatch != null) return quadMatch.groupValues[1].toInt()

        // Voice controls: voice_{tune|mod_depth|env_speed}_N (0-indexed) → quad N/4
        val voiceMatch = Regex("voice_(?:tune|mod_depth|env_speed)_(\\d+)").find(id)
        if (voiceMatch != null) return voiceMatch.groupValues[1].toInt() / 4

        // Duo controls (0-indexed) → quad N/2
        val duoMatch = Regex("voice_(?:duo_mod_source|duo_sharpness|duo_engine|duo_harmonics|duo_morph|duo_mod_source_level)_(\\d+)").find(id)
        if (duoMatch != null) return duoMatch.groupValues[1].toInt() / 2

        // Global/effect controls (voice_vibrato, distortion_*, delay_*, resonator_*, warps_*, etc.)
        return null
    }

    private fun String.toUserRequestPrompt() = """
        ## USER REQUEST
        
        The user asked: \"$this\"

        Here are some guidelines for the composition:
         - Define a preset that allows for creating the sounds in the request above.
         - Use the drums but not too loud or much
         - try to make the key of any songs listed
         - ALWAYS start softly and ease into the song, try not to have too many abrupt changes
         - DO NOT have any high pitch or high volume notes or noises
         - listen for the users command to end/quit/stop. Try to provide a quick but graceful ending. 
    """.trimIndent()

    /**
     * Start the agent.
     * 
     * @param customPrompt Optional custom prompt to inject as additional direction for the composition
     * @param moodName Optional mood name to select (for Solo mode, matches a Mood in SoloAgentConfig)
     * @param userRequest Optional user request description (e.g., "create a song named Midnight Dreams")
     */
    @OptIn(FlowPreview::class)
    fun start(
        compositionType: CompositionType,
        customPrompt: String? = null,
        moodName: String? = null,
        userRequest: String? = null
    ) {
        if (agentJob?.isActive == true) {
            log.debug { "${config.name} already running" }
            return
        }

        log.debug { "Starting ${config.name}" }
        _state.value = SynthAgentState.Starting

        agentJob = scope.launch {
            // Get API key for the current model's provider
            val aiProvider = aiModelProvider.selectedModel.value.aiProvider
            val keyResult = aiKeyRepository.getKey(aiProvider)
            if (keyResult == null) {
                _state.value = SynthAgentState.Error("No API key configured for ${aiProvider.displayName}")
                return@launch
            }
            val (apiKey, _) = keyResult
            try {

                val selectedMood =
                    if (compositionType != CompositionType.USER_PROMPTED && config.moods.isNotEmpty())
                        config.moods.random()
                    else null

                // Input Stream: Combine Timer Ticks and Synth Changes
                val timerFlow =
                    if (selectedMood != null && selectedMood.evolutionPrompts.isNotEmpty()) {
                        flow {
                            var evolutionIndex = 0
                            while (isActive) {
                                // Check if we've completed all prompts and should finish
                                if (evolutionIndex >= selectedMood.evolutionPrompts.size) {
                                    if (config.finishOnLastEvolution) {
                                        // Signal completion and stop the flow
                                        log.debug { "All evolution prompts completed - signaling completion" }
                                        _completed.tryEmit(Unit)
                                        return@flow
                                    } else {
                                        // Cycle back to start (Drone mode behavior)
                                        evolutionIndex = 0
                                    }
                                }
                                // Emit the current evolution prompt
                                emit(selectedMood.evolutionPrompts[evolutionIndex++])
                                delay(config.evolutionIntervalMs)
                            }
                        }
                    } else emptyFlow()

                val synthChangeFlow = synthController.onControlChange
                    .filter { it.origin == ControlEventOrigin.UI }
                    .map { event ->
                        when {
                            event.controlId == DistortionSymbol.DRIVE.controlId.key -> "Drive: ${event.value.format(2)}"
                            event.controlId == DistortionSymbol.MIX.controlId.key -> "Distortion: ${event.value.format(2)}"
                            event.controlId == DelaySymbol.MIX.controlId.key -> "Delay Mix: ${event.value.format(2)}"
                            event.controlId == DelaySymbol.FEEDBACK.controlId.key -> "Delay FB: ${event.value.format(2)}"
                            event.controlId.startsWith(VOICE_URI) && event.controlId.contains("quad_pitch") -> "Quad Pitches"
                            event.controlId.startsWith(VOICE_URI) && event.controlId.contains("quad_hold") -> "Quad Holds"
                            else -> null
                        }
                    }
                    .filterNotNull()
                // Debounce rapid changes (e.g. dragging a slider)
                .debounce(2000.milliseconds)
                .filter {
                    val now = Clock.System.now()
                    // Ignore changes if we just acted (prevent feedback loop)
                    val timeSinceAgentAction = now.toEpochMilliseconds() - lastAgentActionTime.toEpochMilliseconds()
                    // Ignore if we throttled user events recently
                    val timeSinceLastEvent = now.toEpochMilliseconds() - lastActionTime.toEpochMilliseconds()
                    
                    timeSinceAgentAction > 5000 && timeSinceLastEvent > config.throttleIntervalMs
                }
                .map { change -> 
                   "User adjusted: $change"
                }

                // User influence prompts
                val userPromptFlow = _userPrompts.map { "User direction: $it" }
                
                val inputFlow = merge(timerFlow, synthChangeFlow, userPromptFlow)
                
                val synthConfig = config

                // Strategy using Structured Streaming
                val strategy = strategy<Unit, Unit>(synthConfig.name + "_Loop") {
                    val loopNode by node<Unit, Unit> {
                        // Access the LLM context inside the node
                        llm.writeSession {
                            val session = this
                            log.debug { "Session started (Structured)" }
                            
                            val mdDefinition = synthActionDefinition()

                            // Helper to process response stream
                            suspend fun processStructuredResponse(stream: Flow<StreamFrame>) {
                                isBusy = true
                                runCatchingSuspend {
                                    val monitoredStream = stream.onEach {
                                        if (it is StreamFrame.Append) log.debug { "RAW APPEND: ${it.text}" }
                                    }

                                    parseSynthActions(monitoredStream).collect { action ->
                                        processAction(action)
                                    }
                                }.onFailure { e ->
                                    log.error(e) { "Streaming error" }
                                    emitStatus("Stream Error", isError = true)
                                }
                                isBusy = false
                            }
                            
                            var initPrompt = synthConfig.initialPrompt
                            var userLogMessage = "Initializing ${synthConfig.name}..."

                            // Add user request context if provided (from OrpheusAgent)
                            if (userRequest != null) {
                                initPrompt += userRequest.toUserRequestPrompt()
                                userLogMessage = userRequest
                            }

                            if (selectedMood != null) {
                                initPrompt += "\n\nSpecific Direction: ${selectedMood.name}\n${selectedMood.initialPrompt}"
                                if (userRequest == null) {
                                    userLogMessage = "Mood: ${selectedMood.name}"
                                }
                            } else if (synthConfig.initialMoodPrompts.isNotEmpty()) {
                                val mood = synthConfig.initialMoodPrompts.random()
                                initPrompt += "\n\nSpecific Direction:\n$mood"
                                if (userRequest == null) {
                                    userLogMessage = mood
                                }
                            }

                            // Add custom prompt if provided (additional creative direction)
                            if (customPrompt != null) {
                                initPrompt += "\n\nADDITIONAL DIRECTION:\n$customPrompt"
                            }

                            emitStatus("Initializing...", isLoading = true)
                            emitInput(userLogMessage)
                            appendPrompt { user(initPrompt) }
                            processStructuredResponse(requestLLMStreaming(mdDefinition))
        
                            inputFlow.collect { inputPrompt ->
                                if (isActive && !isBusy) {
                                     log.debug { "Event received: $inputPrompt" }
                                     _state.value = SynthAgentState.Processing
                                     emitInput(inputPrompt)
                                     
                                     appendPrompt { user(inputPrompt) }
                                     processStructuredResponse(requestLLMStreaming(mdDefinition))
                                     
                                     lastActionTime = Clock.System.now()
                                }
                            }
                        }
                    }
                    
                    edge(nodeStart forwardTo loopNode)
                    edge(loopNode forwardTo nodeFinish)
                }

                val aiProvider = aiModelProvider.selectedModel.value.aiProvider

                val llmClient: LLMClient = when (aiProvider) {
                    AiProvider.Google -> GoogleLLMClient(apiKey)
                    AiProvider.Anthropic -> AnthropicLLMClient(apiKey)
                    else -> throw IllegalStateException("Unsupported AI provider: $aiProvider")
                }
                val executor = SingleLLMPromptExecutor(llmClient)
                // Tools are manually invoked, so registry can be empty or contain them for metadata (optional)
                val toolRegistry = ToolRegistry {
                    tool(synthControlTool)
                    tool(replExecuteTool)
                }
                
                val agent = AIAgent(
                    promptExecutor = executor,
                    strategy = strategy,
                    agentConfig = AIAgentConfig(
                        prompt = prompt(config.name) {
                             system(config.systemPrompt + """
                                 
                                 IMPORTANT: You must rely on the structured output format provided. Do not use native tool calls.
                                 
                                 BEATS/DRUMS CONTROL:
                                 To START beats: set beats_bpm, beats_mix, beats_density_0/1/2, then set beats_run to 1.0.
                                 To STOP beats: set beats_run to 0.0.

                                 Available controls:
                                 - beats_run: 1.0 = Start sequencer, 0.0 = Stop sequencer
                                 - beats_bpm: 60.0 to 200.0 (tempo)
                                 - beats_mix: 0.0 to 1.0 (volume)
                                 - beats_x, beats_y: 0.0 to 1.0 (pattern morphing)
                                 - beats_density_0, beats_density_1, beats_density_2: 0.0 to 1.0 (kick, snare, hi-hat density)
                                 - beats_randomness, beats_swing
                                 - beats_mode: 0.0 (Drums) or 1.0 (Euclidean)
                             """.trimIndent())
                        },
                        model = aiModelProvider.currentKoogModel,
                        maxAgentIterations = Int.MAX_VALUE // Run indefinitely
                    ),
                    toolRegistry = toolRegistry
                )
                
                log.debug { "Running agent loop..." }
                agent.run(Unit)
                log.debug { "Agent loop finished naturally." }

            } catch (e: CancellationException) {
                log.debug { "Agent job cancelled" }
                throw e
            } catch (e: Exception) {
                log.error(e) { "Agent error" }
                _state.value = SynthAgentState.Error(e.message ?: "Unknown error")
                emitStatus("Error: ${e.message}", isError = true)
            }
        }
    }

    /**
     * Stop the agent and silence.
     */
    fun stop() {
        log.debug { "Stopping ${config.name}" }
        agentJob?.cancel()
        agentJob = null

        // Silence with ramp down
        scope.launch {
            runCatchingSuspend {
                // Save current volumes for the quads we're going to fade
                val savedVolumes = config.activeQuads.associateWith { synthEngine.getQuadVolume(it) }

                // Fade out only the quads this agent uses
                val fadeDuration = 2.0f  // seconds
                log.debug { "Fading out quads ${config.activeQuads} over ${fadeDuration}s" }
                config.activeQuads.forEach { quadIndex ->
                    synthEngine.fadeQuadVolume(quadIndex, 0f, fadeDuration)
                }
                
                // Wait for fade to complete
                delay((fadeDuration * 1000).toLong())

                replExecuteTool.execute(ReplExecuteArgs(lines = listOf("hush")))

                // Restore volumes after hush (instant, not faded)
                savedVolumes.forEach { (quadIndex, volume) ->
                    synthEngine.setQuadVolume(quadIndex, volume)
                }

                log.debug { "Restored quad volumes" }
            }.onFailure { e ->
                log.warn(e) { "Failed to stop gracefully: ${e.message}" }
                replExecuteTool.execute(ReplExecuteArgs(lines = listOf("hush")))
            }
            emitStatus("Stopped")
            _state.value = SynthAgentState.Idle
        }
    }


    @Inject
    class Factory(
        private val aiKeyRepository: AiKeyRepository,
        private val aiModelProvider: AiModelProvider,
        private val synthControlTool: SynthControlTool,
        private val replExecuteTool: ReplExecuteTool,
        private val synthEngine: SynthEngine,
        private val synthController: SynthController,
    ) {
        fun create(config: SynthControlAgentConfig): SynthControlAgent {
            return SynthControlAgent(
                config = config,
                aiKeyRepository = aiKeyRepository,
                aiModelProvider = aiModelProvider,
                synthControlTool = synthControlTool,
                replExecuteTool = replExecuteTool,
                synthEngine = synthEngine,
                synthController = synthController
            )
        }
    }
}
