package org.balch.orpheus.features.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent.FeatureContext
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.ai.anthropicModelVersionsMap
import org.balch.orpheus.core.ai.deriveAiProviderFromKey
import org.balch.orpheus.core.ai.usesAdaptiveThinking
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.AgentGreetingMode
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.tidal.ReplCodeEvent
import org.balch.orpheus.core.tidal.ReplCodeEventBus
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.session.AgentSessionStats
import org.balch.orpheus.features.ai.session.SessionUsage
import kotlin.time.ExperimentalTime

private const val ANTHROPIC_THINKING_BUDGET = 4096   // ≥1024, counts toward maxTokens; tune for latency/quality

// Koog's Anthropic request defaults max_tokens to MAX_TOKENS_DEFAULT = 2048 — LESS than the
// thinking budget above, and Anthropic rejects that pairing outright (400: "max_tokens must
// be greater than thinking.budget_tokens"). Always set it explicitly. Thinking (budgeted OR
// adaptive) counts toward max_tokens, so leave generous room for the visible reply too.
private const val ANTHROPIC_MAX_TOKENS = 16_000

/**
 * Anthropic thinking config, shaped per model generation.
 *
 * Models flagged [usesAdaptiveThinking] reject `thinking: {type: "enabled",
 * budget_tokens}` with a 400 (Opus 4.7+ / Sonnet 5 / Fable 5). Koog 1.0.0 has no
 * adaptive variant of [AnthropicThinking], so the raw object rides
 * additionalProperties, which Koog's AnthropicMessageRequestSerializer flattens into
 * the request body. `display: "summarized"` matters: these models default to omitted
 * thinking text, which would leave the apps' thinking feeds permanently empty.
 */
internal fun anthropicThinkingParams(model: LLModel): AnthropicParams =
    if (model.usesAdaptiveThinking) {
        AnthropicParams(
            maxTokens = ANTHROPIC_MAX_TOKENS,
            additionalProperties = mapOf(
                "thinking" to buildJsonObject {
                    put("type", "adaptive")
                    put("display", "summarized")
                },
            ),
        )
    } else {
        AnthropicParams(
            maxTokens = ANTHROPIC_MAX_TOKENS,
            thinking = AnthropicThinking.Enabled(budgetTokens = ANTHROPIC_THINKING_BUDGET),
        )
    }

/**
 * Orpheus AI Agent - a musical guide inhabiting the Orphic-FM synthesizer.
 * Uses Gemini to provide expert advice on sounds and can control the synth.
 */
@SingleIn(FeatureScope::class)
@Inject
class OrpheusAgent(
    private val config: OrpheusAgentConfig,
    private val aiKeyRepository: AiKeyRepository,
    private val aiModelProvider: AiModelProvider,
    private val replCodeEventBus: ReplCodeEventBus,
    private val agentActivityEventBus: AgentActivityEventBus,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
    private val greetingMode: AgentGreetingMode,
) {
    private val logger = logging("OrpheusAgent")

    private val userIntent = MutableSharedFlow<PromptIntent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _sessionUsage = MutableStateFlow(SessionUsage.EMPTY)
    val sessionUsage: StateFlow<SessionUsage> = _sessionUsage.asStateFlow()

    private var currentAgentSessionStats = AgentSessionStats()

    private fun trackInputTokens(text: String) {
        val tokens = text.split(Regex("\\s+")).size
        currentAgentSessionStats = currentAgentSessionStats.copy(
            inputTokens = currentAgentSessionStats.inputTokens + tokens
        )
        _sessionUsage.update { it.copy(inputTokens = it.inputTokens + tokens) }
    }

    private fun trackOutputTokens(text: String) {
        val tokens = text.split(Regex("\\s+")).size
        currentAgentSessionStats = currentAgentSessionStats.copy(
            outputTokens = currentAgentSessionStats.outputTokens + tokens
        )
        _sessionUsage.update { it.copy(outputTokens = it.outputTokens + tokens) }
    }

    private fun trackToolCall() {
        currentAgentSessionStats = currentAgentSessionStats.copy(
            toolCalls = currentAgentSessionStats.toolCalls + 1
        )
        _sessionUsage.update { it.copy(toolCalls = it.toolCalls + 1) }
    }

    private fun finalizeRoundTrip() {
        if (currentAgentSessionStats.inputTokens > 0 || currentAgentSessionStats.outputTokens > 0) {
            _sessionUsage.update { current ->
                current.copy(sessionHistory = current.sessionHistory + currentAgentSessionStats)
            }
            currentAgentSessionStats = AgentSessionStats()
        }
    }

    fun sendPrompt(prompt: PromptIntent) {
        userIntent.tryEmit(prompt)
        // A failed run leaves the loop dead (its flow completed after the error). Re-arm so
        // this prompt gets consumed: ON_FIRST_PROMPT picks the just-buffered intent straight
        // from the replay cache; ON_START re-greets, then the run loop consumes it.
        startAgentIfNeeded()
    }

    fun sendReplPrompt(
        displayText: String,
        selectedMood: String,
        selectedMode: String,
        selectedKey: String,
    ) {
        sendPrompt(
            PromptIntent(
                prompt = config.getReplPrompt(selectedMood, selectedMode, selectedKey),
                displayText = displayText
            )
        )
    }

    fun sendPrompt(text: String) {
        sendPrompt(PromptIntent(text))
    }

    private val _statusMessages = MutableSharedFlow<AiStatusMessage>(replay = 10, extraBufferCapacity = 10)
    val statusMessages: SharedFlow<AiStatusMessage> = _statusMessages.asSharedFlow()

    private fun emitStatus(text: String, isLoading: Boolean = false, isError: Boolean = false) {
        _statusMessages.tryEmit(AiStatusMessage(text, isLoading, isError))
    }

    private val initialMessage = ChatMessage(text = "Awakening...", type = ChatMessageType.Loading)
    private val messages = mutableListOf(initialMessage)

    // Check if current provider has a default key (sync check for UI state)
    val isApiKeySet: Boolean
        get() = aiModelProvider.selectedModel.value
            .aiProvider
            .defaultKey() != null

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Loading(messages.toList()))
    
    private var currentAgentJob: Job? = null

    val agentFlow: StateFlow<AgentState> = _agentState.asStateFlow()

    init {
        scope.launch(dispatcherProvider.io) {
            replCodeEventBus.events.collect { event ->
                when (event) {
                    is ReplCodeEvent.Generating -> emitStatus("Generating code...", isLoading = true)
                    is ReplCodeEvent.Generated -> {
                        val slotsText = if (event.slots.isNotEmpty()) event.slots.joinToString(", ") else "pattern"
                        emitStatus("Code ready: $slotsText")
                    }
                    is ReplCodeEvent.Failed -> emitStatus("Code generation failed: ${event.error}", isError = true)
                    is ReplCodeEvent.UserInteraction -> {}
                }
            }
        }
        startAgentIfNeeded()
    }
    
    private fun startAgentIfNeeded() {
        // "IfNeeded" enforced here: live run loops are never doubled. restart() manages its
        // own cancel + relaunch and does not route through this guard.
        if (currentAgentJob?.isActive == true) return
        currentAgentJob = scope.launch(dispatcherProvider.io) {
            // Get API key for the current model's provider
            val aiProvider = aiModelProvider.selectedModel.value.aiProvider
            val keyResult = aiKeyRepository.getKey(aiProvider)
            if (keyResult == null) {
                _agentState.value = AgentState.Error(
                    IllegalStateException("No API key"),
                    listOf(ChatMessage(
                        text = "Orpheus awaits... but no API key is configured for ${aiProvider.displayName}.\n\nAdd an API key to local.properties to awaken.",
                        type = ChatMessageType.Error
                    ))
                )
                return@launch
            }
            val (apiKey, _) = keyResult

            // Choose the prompt that seeds the run. ON_START greets immediately; ON_FIRST_PROMPT
            // suspends here until the user submits, so merely opening the panel issues no LLM request.
            val seedPrompt = when (greetingMode) {
                AgentGreetingMode.ON_START -> config.initialAgentPrompt()
                AgentGreetingMode.ON_FIRST_PROMPT -> {
                    // SUSPEND here until the user submits: no LLM client is built and no request is
                    // sent until then. We do NOT reset the replay cache before suspending — a fresh
                    // start has none, and a prompt the user submits during the getKey() await above
                    // must be preserved (replay=1 buffers it and first() returns it). Stale prompts
                    // from a prior run are cleared in restart() before it re-arms this coroutine.
                    val intent = userIntent.first()
                    userIntent.resetReplayCache()           // clear the just-consumed prompt so the run-loop's own userIntent.first() suspends correctly next turn
                    intent.prompt
                }
            }

            runAgent(seedPrompt, apiKey)
                .flowOn(Dispatchers.Default)
                .catch { throwable ->
                    logger.error(throwable) { "Unhandled exception in agent flow" }
                    agentActivityEventBus.emitError(throwable.message ?: "An error occurred")
                    _agentState.value = errorMessageAsState(throwable, throwable.message ?: "An error occurred")
                }
                .collect { state ->
                    _agentState.value = state
                }
        }
    }

    fun restart() {
        // Cancel the current run before re-arming, regardless of greeting mode.
        currentAgentJob?.cancel()
        currentAgentJob = null

        if (greetingMode == AgentGreetingMode.ON_FIRST_PROMPT) {
            // Silent restart: drop any prompt left over from the cancelled run so the re-armed
            // coroutine truly waits for a NEW submit, then re-arm and suspend on the next user
            // prompt (startAgentIfNeeded reads config.model fresh on the next run). Send NO
            // greeting/model-switch prompt.
            userIntent.resetReplayCache()
            startAgentIfNeeded()
            return
        }

        // ON_START: greet the user with a model-switch message (Orpheus behavior, unchanged).
        val modelName = config.model.toString()
            .substringAfterLast(".")
            .replace("_", " ")
            .lowercase()
            .replaceFirstChar { it.uppercase() }

        logger.debug { "Restarting agent with model: $modelName" }

        messages.add(ChatMessage(text = "Switching to $modelName...", type = ChatMessageType.Loading))
        _agentState.value = AgentState.Loading(messages.toList())

        currentAgentJob = scope.launch(dispatcherProvider.io) {
            // Get API key for the current model's provider
            val aiProvider = aiModelProvider.selectedModel.value.aiProvider
            val keyResult = aiKeyRepository.getKey(aiProvider)
            if (keyResult == null) {
                _agentState.value = AgentState.Error(
                    IllegalStateException("No API key"),
                    listOf(ChatMessage(
                        text = "Cannot restart - no API key configured for ${aiProvider.displayName}.",
                        type = ChatMessageType.Error
                    ))
                )
                return@launch
            }
            val (apiKey, _) = keyResult
            
            runAgent("You just switched to a new AI model ($modelName). " +
                    "Briefly greet the user as Orpheus and mention you're now using $modelName. " +
                    "Be concise - one sentence is enough. Then wait for the user's next message.", apiKey)
                .flowOn(Dispatchers.Default)
                .catch { throwable ->
                    logger.error(throwable) { "Unhandled exception in agent flow after restart" }
                    agentActivityEventBus.emitError(throwable.message ?: "An error occurred")
                    _agentState.value = errorMessageAsState(throwable, throwable.message ?: "An error occurred")
                }
                .collect { state ->
                    _agentState.value = state
                }
        }
    }

    @OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
    private fun runAgent(prompt: String, apiKey: String): Flow<AgentState> = channelFlow {
        val strategy = config.agentStrategy(
            name = "OrpheusAgent",
            onAssistantMessage = { message ->
                agentActivityEventBus.emitAssistant(message)
                send(agentMessageToState(message))
                val userPrompt = userIntent.first()
                userIntent.resetReplayCache()
                send(userMessageToState(userPrompt.displayText))
                userPrompt.prompt
            },
            onReasoning = { agentActivityEventBus.emitReasoning(it) },
            // See agentStrategy: Anthropic needs the batched nodes (signed thinking replay
            // + correct history); Google keeps live streaming.
            streamResponses = aiModelProvider.selectedModel.value.aiProvider != AiProvider.Anthropic,
        )

        createAgent(strategy, apiKey) {
            // Lifecycle logging handler
            handleEvents {
                onAgentStarting { _ -> logger.d { "Agent starting" } }
                onAgentCompleted { _ -> logger.d { "Agent completed" } }
                onToolCallStarting { context ->
                    logger.d { "Tool call starting: ${context.toolName}" }
                    agentActivityEventBus.emitToolStarted(context.toolName)
                }
                onToolCallCompleted { context ->
                    logger.d { "Tool call completed: ${context.toolName}" }
                    agentActivityEventBus.emitToolCompleted(context.toolName)
                }
            }
            // Error handling handler
            handleEvents {
                onAgentExecutionFailed { ctx ->
                    if (ctx.error is CancellationException) {
                        logger.debug { "Agent execution cancelled" }
                    } else {
                        logger.error(ctx.error) { "Error running agent" }
                        send(errorMessageAsState(ctx.error, "Something went wrong..."))
                        agentActivityEventBus.emitError(ctx.error.message ?: "Something went wrong")
                    }
                }
                onToolCallFailed { context -> logger.e { "Tool call failed: ${context.toolName} : ${context.message}" } }
            }
            // Usage tracking handler
            handleEvents {
                onToolCallCompleted { _ -> trackToolCall() }
            }
        }.run(prompt)
    }.onEach {
        logger.d { "Agent state: $it" }
    }.catch { throwable ->
        logger.error(throwable) { "Unhandled exception in agent flow" }
        agentActivityEventBus.emitError(throwable.message ?: "An error occurred")
        emit(errorMessageAsState(throwable, throwable.message ?: "An error occurred"))
    }

    private fun userMessageToState(message: String): AgentState {
        trackInputTokens(message)
        messages.add(ChatMessage(text = message, type = ChatMessageType.User))
        messages.add(ChatMessage(text = "Thinking...", type = ChatMessageType.Loading))
        return AgentState.Chatting(messages.toList())
    }

    private fun agentMessageToState(message: String): AgentState {
        trackOutputTokens(message)
        finalizeRoundTrip()
        addOrReplaceMessage(ChatMessage(text = message, type = ChatMessageType.Agent))
        return AgentState.Chatting(messages.toList())
    }

    private fun errorMessageAsState(exception: Throwable, message: String): AgentState {
        addOrReplaceMessage(ChatMessage(text = message, type = ChatMessageType.Error))
        return AgentState.Error(exception, messages.toList())
    }

    private fun addOrReplaceMessage(message: ChatMessage) {
        val lastMessage = messages.lastOrNull()
        if (lastMessage?.type == ChatMessageType.Loading) {
            messages[messages.lastIndex] = message
        } else {
            messages.add(message)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun createAgent(
        strategy: AIAgentGraphStrategy<String, String>,
        apiKey: String,
        installFeatures: FeatureContext.() -> Unit = {},
    ): AIAgent<String, String> {
        val aiProvider = deriveAiProviderFromKey(key = apiKey)
        val httpFactory = KtorKoogHttpClient.Factory()
        val llmClient: LLMClient = when (aiProvider) {
            AiProvider.Google -> GoogleLLMClient(apiKey, httpClientFactory = httpFactory)
            // The extended versions map is REQUIRED: Koog 1.0.0's serializer throws
            // "Unsupported model" for any LLModel absent from it (all post-4.7 models).
            AiProvider.Anthropic -> AnthropicLLMClient(
                apiKey,
                settings = AnthropicClientSettings(modelVersionsMap = anthropicModelVersionsMap),
                httpClientFactory = httpFactory,
            )
            AiProvider.OpenAI -> OpenAILLMClient(apiKey, httpClientFactory = httpFactory)
            else -> throw IllegalStateException("Unsupported AI provider: $aiProvider")
        }
        val executor = MultiLLMPromptExecutor(llmClient)

        val thinkingParams: LLMParams =
            if (config.model.supports(LLMCapability.Thinking)) {
                when (aiProvider) {
                    AiProvider.Anthropic -> anthropicThinkingParams(config.model)
                    AiProvider.Google -> GoogleParams(thinkingConfig = GoogleThinkingConfig(includeThoughts = true, thinkingLevel = GoogleThinkingLevel.HIGH))
                    AiProvider.OpenAI -> OpenAIResponsesParams(reasoning = ReasoningConfig(effort = ReasoningEffort.MEDIUM, summary = ReasoningSummary.AUTO))
                    else -> LLMParams()
                }
            } else {
                LLMParams()
            }

        val agentConfig = AIAgentConfig(
            prompt = prompt("OrpheusAgent", thinkingParams) {
                system(config.systemInstruction)
            },
            model = config.model,
            maxAgentIterations = config.maxAgentIterations
        )

        return AIAgent(
            promptExecutor = executor,
            strategy = strategy,
            agentConfig = agentConfig,
            toolRegistry = config.toolRegistry,
            installFeatures = installFeatures,
        )
    }

    fun addExternalMessage(text: String, type: ChatMessageType) {
        messages.add(ChatMessage(text = text, type = type))
        _agentState.value = AgentState.Chatting(messages.toList())
    }

}
