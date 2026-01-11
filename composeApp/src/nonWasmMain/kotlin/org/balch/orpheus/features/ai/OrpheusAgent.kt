package org.balch.orpheus.features.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent.FeatureContext
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.balch.orpheus.core.ai.GeminiKeyProvider
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.session.AgentSessionStats
import org.balch.orpheus.features.ai.session.SessionUsage
import kotlin.time.ExperimentalTime

/**
 * Orpheus AI Agent - a musical guide inhabiting the Orphic-FM synthesizer.
 * Uses Gemini to provide expert advice on sounds and can control the synth.
 */
@SingleIn(AppScope::class)
actual class OrpheusAgent @Inject constructor(
    private val config: OrpheusAgentConfig,
    private val geminiKeyProvider: GeminiKeyProvider,
    private val replCodeEventBus: ReplCodeEventBus,
) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = logging("OrpheusAgent")

    private val userIntent = MutableSharedFlow<PromptIntent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _sessionUsage = MutableStateFlow(SessionUsage.EMPTY)
    actual val sessionUsage: StateFlow<SessionUsage> = _sessionUsage.asStateFlow()

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

    actual fun sendPrompt(prompt: PromptIntent) {
        userIntent.tryEmit(prompt)
    }

    actual fun sendReplPrompt(
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

    actual fun sendPrompt(text: String) {
        sendPrompt(PromptIntent(text))
    }

    private val _statusMessages = MutableSharedFlow<AiStatusMessage>(replay = 10, extraBufferCapacity = 10)
    actual val statusMessages: SharedFlow<AiStatusMessage> = _statusMessages.asSharedFlow()

    private fun emitStatus(text: String, isLoading: Boolean = false, isError: Boolean = false) {
        _statusMessages.tryEmit(AiStatusMessage(text, isLoading, isError))
    }

    private val initialMessage = ChatMessage(text = "Awakening...", type = ChatMessageType.Loading)
    private val messages = mutableListOf(initialMessage)

    actual val isApiKeySet: Boolean get() = geminiKeyProvider.isApiKeySet

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Loading(messages.toList()))
    
    private var currentAgentJob: Job? = null

    actual val agentFlow: StateFlow<AgentState> = _agentState.asStateFlow()

    init {
        applicationScope.launch {
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
        if (!geminiKeyProvider.isApiKeySet) {
            _agentState.value = AgentState.Error(
                IllegalStateException("No API key"),
                listOf(ChatMessage(
                    text = "Orpheus awaits... but no API key is configured.\n\nAdd GEMINI_API_KEY to local.properties to awaken.",
                    type = ChatMessageType.Error
                ))
            )
            return
        }
        
        currentAgentJob = applicationScope.launch {
            runAgent(config.initialAgentPrompt())
                .flowOn(Dispatchers.Default)
                .catch { throwable ->
                    logger.error(throwable) { "Unhandled exception in agent flow" }
                    _agentState.value = errorMessageAsState(throwable, throwable.message ?: "An error occurred")
                }
                .collect { state ->
                    _agentState.value = state
                }
        }
    }

    actual fun restart() {
        val modelName = config.model.toString()
            .substringAfterLast(".")
            .replace("_", " ")
            .lowercase()
            .replaceFirstChar { it.uppercase() }
        
        logger.debug { "Restarting agent with model: $modelName" }
        
        currentAgentJob?.cancel()
        currentAgentJob = null
        
        messages.add(ChatMessage(text = "Switching to $modelName...", type = ChatMessageType.Loading))
        _agentState.value = AgentState.Loading(messages.toList())
        
        currentAgentJob = applicationScope.launch {
            runAgent("You just switched to a new AI model ($modelName). " +
                    "Briefly greet the user as Orpheus and mention you're now using $modelName. " +
                    "Be concise - one sentence is enough. Then wait for the user's next message.")
                .flowOn(Dispatchers.Default)
                .catch { throwable ->
                    logger.error(throwable) { "Unhandled exception in agent flow after restart" }
                    _agentState.value = errorMessageAsState(throwable, throwable.message ?: "An error occurred")
                }
                .collect { state ->
                    _agentState.value = state
                }
        }
    }

    @OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
    private fun runAgent(prompt: String): Flow<AgentState> = channelFlow {
        val strategy = config.agentStrategy(
            name = "OrpheusAgent",
            onAssistantMessage = { message ->
                send(agentMessageToState(message))
                val userPrompt = userIntent.first()
                userIntent.resetReplayCache()
                send(userMessageToState(userPrompt.displayText))
                userPrompt.prompt
            }
        )

        createAgent(strategy) {
            handleEvents {
                onAgentStarting { _ -> logger.d { "Agent starting" } }
                onAgentCompleted { _ -> logger.d { "Agent completed" } }
                onAgentExecutionFailed { ctx ->
                    if (ctx.throwable is CancellationException) {
                        logger.debug { "Agent execution cancelled" }
                    } else {
                        logger.error(ctx.throwable) { "Error running agent" }
                        send(errorMessageAsState(ctx.throwable, "Something went wrong..."))
                    }
                }
                onToolCallFailed { context -> logger.e { "Tool call failed: ${context.toolName} : ${context.message}" } }
                onToolCallStarting { context -> logger.d { "Tool call starting: ${context.toolName}" } }
                onToolCallCompleted { context ->
                    logger.d { "Tool call completed: ${context.toolName}" }
                    trackToolCall()
                }
            }
        }.run(prompt)
    }.onEach {
        logger.d { "Agent state: $it" }
    }.catch { throwable ->
        logger.error(throwable) { "Unhandled exception in agent flow" }
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
        installFeatures: FeatureContext.() -> Unit = {},
    ): AIAgent<String, String> {
        val llmClient = GoogleLLMClient(geminiKeyProvider.apiKey!!)
        val executor = SingleLLMPromptExecutor(llmClient)

        val agentConfig = AIAgentConfig(
            prompt = prompt("OrpheusAgent") {
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

    actual fun addExternalMessage(text: String, type: ChatMessageType) {
        messages.add(ChatMessage(text = text, type = type))
        _agentState.value = AgentState.Chatting(messages.toList())
    }
}
