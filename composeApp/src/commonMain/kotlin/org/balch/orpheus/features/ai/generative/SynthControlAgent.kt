package org.balch.orpheus.features.ai.generative

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.GeminiKeyProvider
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.features.ai.tools.ReplExecuteTool
import org.balch.orpheus.features.ai.tools.SynthControlTool
import kotlin.time.Duration.Companion.milliseconds

/**
 * Helper for formatting floats
 */
private fun Float.format(digits: Int): String {
    val s = toString()
    val i = s.indexOf('.')
    return if (i >= 0 && i + digits + 1 < s.length) s.substring(0, i + digits + 1) else s
}

/**
 * State of the Synth Control Agent.
 */
sealed interface SynthAgentState {
    data object Idle : SynthAgentState
    data object Starting : SynthAgentState
    data object Processing : SynthAgentState
    data class Playing(val description: String) : SynthAgentState
    data class Error(val message: String) : SynthAgentState
}

/**
 * A status message from the Agent for display.
 */
data class AiStatusMessage(
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

/**
 * Generic AI Agent that controls the synth based on a configuration.
 * Can be used for "Drone" mode, "Solo" mode, etc.
 *
 * Use [SynthControlAgent.Factory] to create instances.
 */
class SynthControlAgent(
    private val config: SynthControlAgentConfig,
    private val geminiKeyProvider: GeminiKeyProvider,
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
    
    // User influence prompts (for Solo mode)
    private val _userPrompts = MutableSharedFlow<String>(extraBufferCapacity = 5)
    
    /**
     * Inject a user prompt into the agent's input stream.
     * Used by Solo mode to let users influence the composition.
     */
    fun injectUserPrompt(text: String) {
        log.info { "User influence: $text" }
        _userPrompts.tryEmit(text)
    }
    
    // JSON decoder for tool args
    private val json = Json { ignoreUnknownKeys = true }

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
                
                log.info { "Executing CONTROL: $id = $value" }
                emitControl("Set $id: ${value.format(2)}")
                val result = synthControlTool.execute(SynthControlTool.Args(id, value))
                if (!result.success) emitControl("Failed: ${result.message}", isError = true)
            }
            ActionType.REPL -> {
                val code = action.details.getOrNull(0) ?: return
                
                // Block hush commands - the agent should maintain continuous sound
                if (code.equals("hush", ignoreCase = true)) {
                    log.warn { "Blocked 'hush' command - continuous playback required" }
                    emitControl("(hush blocked - maintaining flow)", isError = false)
                    return
                }

                log.info { "Executing REPL: $code" }
                emitControl("Pattern: $code")
                val result = replExecuteTool.execute(ReplExecuteTool.Args(code))
                if (!result.success) emitControl("Failed: ${result.message}", isError = true)
            }
            ActionType.STATUS -> {
                val msg = action.details.getOrNull(0) ?: return
                log.info { "STATUS: $msg" }
                _state.value = SynthAgentState.Playing(msg)
                emitStatus(msg)
            }
            ActionType.UNKNOWN -> {
                // Ignore
            }
        }
    }

    /**
     * Start the agent.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!geminiKeyProvider.isApiKeySet) {
            _state.value = SynthAgentState.Error("No API key configured")
            return
        }

        if (agentJob?.isActive == true) {
            log.debug { "${config.name} already running" }
            return
        }

        log.info { "Starting ${config.name}" }
        _state.value = SynthAgentState.Starting
        val apiKey = geminiKeyProvider.apiKey ?: return

        agentJob = scope.launch {
            try {
                val selectedMood = if (config.moods.isNotEmpty()) config.moods.random() else null

                // Input Stream: Combine Timer Ticks and Synth Changes
                val timerFlow = flow {
                    var evolutionIndex = 0
                    while (isActive) {
                        delay(config.evolutionIntervalMs)
                        if (selectedMood != null && selectedMood.evolutionPrompts.isNotEmpty()) {
                            // Emit evolution prompts sequentially, cycling through the list
                            emit(selectedMood.evolutionPrompts[evolutionIndex++])
                            if (evolutionIndex >= selectedMood.evolutionPrompts.size) {
                                evolutionIndex = 0
                            }

                        } else if (config.initialMoodPrompts.isNotEmpty()) {
                            emit(config.initialMoodPrompts.random())
                        }
                    }
                }

                val synthChangeFlow = synthController.onControlChange
                    .filter { it.origin == ControlEventOrigin.UI }
                    .map { event ->
                        when {
                            event.controlId == "drive" -> "Drive: ${event.value.format(2)}"
                            event.controlId == "distortion_mix" -> "Distortion: ${event.value.format(2)}"
                            event.controlId == "delay_mix" -> "Delay Mix: ${event.value.format(2)}"
                            event.controlId == "delay_feedback" -> "Delay FB: ${event.value.format(2)}"
                            event.controlId.startsWith("quad_") && event.controlId.endsWith("_pitch") -> "Quad Pitches"
                            event.controlId.startsWith("quad_") && event.controlId.endsWith("_hold") -> "Quad Holds"
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
                            log.info { "Session started (Structured)" }
                            
                            val mdDefinition = synthActionDefinition()

                            // Helper to process response stream
                            suspend fun processStructuredResponse(stream: Flow<StreamFrame>) {
                                isBusy = true
                                try {
                                    val monitoredStream = stream.onEach { 
                                         if (it is StreamFrame.Append) log.debug { "RAW APPEND: ${it.text}" }
                                    }
                                    
                                    parseSynthActions(monitoredStream).collect { action ->
                                        processAction(action)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                     log.error(e) { "Streaming error" }
                                     emitStatus("Stream Error", isError = true)
                                } finally {
                                    isBusy = false
                                }
                            }
                            
                            
                            var initPrompt = synthConfig.initialPrompt
                            var userLogMessage = "Initializing ${synthConfig.name}..."

                            if (selectedMood != null) {
                                initPrompt += "\n\nSpecific Direction: ${selectedMood.name}\n${selectedMood.initialPrompt}"
                                userLogMessage = "Mood: ${selectedMood.name}"
                            } else if (synthConfig.initialMoodPrompts.isNotEmpty()) {
                                val mood = synthConfig.initialMoodPrompts.random()
                                initPrompt += "\n\nSpecific Direction:\n$mood"
                                userLogMessage = mood
                            }

                            emitStatus("Initializing...", isLoading = true)
                            emitInput(userLogMessage)
                            appendPrompt { user(initPrompt) }
                            processStructuredResponse(requestLLMStreaming(mdDefinition))
        
                            inputFlow.collect { inputPrompt ->
                                if (isActive && !isBusy) {
                                     log.info { "Event received: $inputPrompt" }
                                     _state.value = SynthAgentState.Processing
                                     emitStatus("Evolving...", isLoading = true)
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

                val llmClient = GoogleLLMClient(apiKey)
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
                             system(config.systemPrompt + "\n\nIMPORTANT: You must rely on the structured output format provided. Do not use native tool calls.")
                        },
                        model = aiModelProvider.currentKoogModel,
                        maxAgentIterations = Int.MAX_VALUE // Run indefinitely
                    ),
                    toolRegistry = toolRegistry
                )
                
                log.info { "Running agent loop..." }
                agent.run(Unit)
                log.info { "Agent loop finished naturally." }

            } catch (e: CancellationException) {
                log.info { "Agent job cancelled" }
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
        log.info { "Stopping ${config.name}" }
        agentJob?.cancel()
        agentJob = null

        // Silence with ramp down
        scope.launch {
            try {
                val currentVol = synthEngine.getMasterVolume()
                
                log.debug { "Ramping down master volume from $currentVol" }
                synthEngine.setParameterAutomation(
                    controlId = "master_volume",
                    times = floatArrayOf(0f, 1.5f),
                    values = floatArrayOf(currentVol, 0f),
                    count = 2,
                    duration = 2.0f,
                    mode = 0
                )
                
                delay(1700)
                
                replExecuteTool.execute(ReplExecuteTool.Args(code = "hush"))
                
                synthEngine.clearParameterAutomation("master_volume")
                synthEngine.setMasterVolume(currentVol)
                log.debug { "Restored master volume to $currentVol" }
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Failed to stop gracefully: ${e.message}" }
                replExecuteTool.execute(ReplExecuteTool.Args(code = "hush"))
            } finally {
                emitStatus("Stopped")
                _state.value = SynthAgentState.Idle
            }
        }
    }

    @Inject
    class Factory(
        private val geminiKeyProvider: GeminiKeyProvider,
        private val aiModelProvider: AiModelProvider,
        private val synthControlTool: SynthControlTool,
        private val replExecuteTool: ReplExecuteTool,
        private val synthEngine: SynthEngine,
        private val synthController: SynthController,
    ) {
        fun create(config: SynthControlAgentConfig): SynthControlAgent {
            return SynthControlAgent(
                config = config,
                geminiKeyProvider = geminiKeyProvider,
                aiModelProvider = aiModelProvider,
                synthControlTool = synthControlTool,
                replExecuteTool = replExecuteTool,
                synthEngine = synthEngine,
                synthController = synthController
            )
        }
    }
}
