package org.balch.orpheus.djapp.ai

import androidx.compose.runtime.Composable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.AgentActivityEventBus
import org.balch.orpheus.features.ai.OrpheusAgent
import org.balch.orpheus.features.ai.PromptIntent
import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.VibeCreateEventBus

// ─────────────────────────────────────────────────────────────────────────
// Pure reducers (internal so tests can call them directly without DI)
// ─────────────────────────────────────────────────────────────────────────

/** Tool-name set that indicates a Pulsar vibe operation is under way. */
private val VIBE_TOOLS = setOf("pulsar_apply_vibe", "pulsar_vibe_schema", "pulsar_get_vibe")

private val HEADLINE_RE = Regex("""\*\*(.+?)\*\*""")

/**
 * Append a reasoning chunk to the feed's tail [DjAiFeedItem.Thinking] (creating one when
 * the tail is not a Thinking), then split at each completed `**headline**` marker.
 *
 * Invariant: a Thinking item's text never contains its own headline markers, so any
 * regex match in the tail is a NEW headline. Scanning the accumulated tail text (not
 * just the incoming chunk) handles headlines split across streamed chunks.
 */
internal fun appendReasoning(
    feed: List<DjAiFeedItem>,
    nextId: Long,
    chunk: String,
): Pair<List<DjAiFeedItem>, Long> {
    val tail = feed.lastOrNull()
    if (chunk.isBlank() && tail !is DjAiFeedItem.Thinking) return feed to nextId

    var id = nextId
    val out: MutableList<DjAiFeedItem>
    var current: DjAiFeedItem.Thinking
    if (tail is DjAiFeedItem.Thinking) {
        out = feed.dropLast(1).toMutableList()
        current = tail.copy(text = tail.text + chunk)
    } else {
        out = feed.toMutableList()
        current = DjAiFeedItem.Thinking(id = id++, headline = null, text = chunk)
    }

    while (true) {
        val match = HEADLINE_RE.find(current.text) ?: break
        val label = match.groupValues[1].trim().trim('*', ':', ' ')
        val before = current.text.substring(0, match.range.first)
        val after = current.text.substring(match.range.last + 1)
        when {
            // Degenerate marker (e.g. `** : **`): drop it and keep scanning.
            label.isBlank() -> current = current.copy(text = before + after)
            // Nothing before this segment's first headline: relabel in place, id preserved.
            current.headline == null && before.isBlank() ->
                current = current.copy(headline = label, text = after)
            else -> {
                out += current.copy(text = before)
                current = DjAiFeedItem.Thinking(id = id++, headline = label, text = after)
            }
        }
    }
    out += current
    return out to id
}

/** Shared clear applied on submit() and reset(): a fresh run starts with an empty feed. */
internal fun clearRunOutput(s: DjAiUiState): DjAiUiState =
    s.copy(feed = emptyList(), nextId = 0, error = null)

/**
 * Start a fresh run from [draft]: clear the previous run's output, seed the feed with the
 * [DjAiFeedItem.Request] row (always id 0, always the top row), remember [draft] as
 * [DjAiUiState.lastPrompt] for the model-switch prefill, and blank the input.
 */
internal fun startRun(s: DjAiUiState, draft: String): DjAiUiState =
    clearRunOutput(s).copy(
        phase = DjAiPhase.GENERATING,
        promptDraft = "",
        lastPrompt = draft,
        feed = listOf(DjAiFeedItem.Request(id = 0, text = draft)),
        nextId = 1,
    )

/**
 * State transition for a model switch. Always returns to [DjAiPhase.IDLE]: the agent
 * restart aborts any in-flight run, and without this the panel stayed stuck on the
 * working strip forever (the aborted run's closing events never arrive). Prefills the
 * input with the last submitted prompt so "retry on another model" is one tap — but
 * never clobbers a draft the user is mid-typing.
 */
internal fun applyModelSwitch(s: DjAiUiState): DjAiUiState = s.copy(
    phase = DjAiPhase.IDLE,
    promptDraft = s.promptDraft.ifBlank { s.lastPrompt },
)

/**
 * Convert a raw tool name to a human-readable label.
 * Mirrors the `friendlyTool` helper in `VibeCreateViewModel`.
 */
private fun friendly(tool: String): String = when (tool) {
    "pulsar_vibe_schema" -> "Vibe Schema"
    "pulsar_get_vibe" -> "Current Vibe"
    "pulsar_apply_vibe" -> "Apply Vibe"
    else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Reduce an [AgentActivityEvent] into a new [DjAiUiState].
 *
 * Routing rules:
 * - [AgentActivityEvent.Reasoning] -> appended to / split into the tail
 *   [DjAiFeedItem.Thinking] via [appendReasoning].
 * - [AgentActivityEvent.ToolStarted]/[AgentActivityEvent.ToolCompleted] -> append/flip a
 *   [DjAiFeedItem.Tool] row.
 * - [AgentActivityEvent.Assistant] -> the trailing [DjAiFeedItem.Reply] (latest wins).
 * - [AgentActivityEvent.Error] -> back to [DjAiPhase.IDLE] with [DjAiUiState.error] set;
 *   the feed keeps its failure context. Without this, an LLM/API failure (e.g. a 400)
 *   left the panel spinning in GENERATING forever with the error only in the log.
 * - When a vibe tool starts from [DjAiPhase.IDLE], phase advances to [DjAiPhase.GENERATING].
 */
internal fun reduceActivityEvent(e: AgentActivityEvent, s: DjAiUiState): DjAiUiState {
    val startsVibeRun = e is AgentActivityEvent.ToolStarted && e.tool in VIBE_TOOLS
    // Process events in both GENERATING and RESULT: VibeApplyTool emits VibeCreateEvent.Generated
    // (flipping GENERATING -> RESULT) BEFORE Koog fires the trailing ToolCompleted + Assistant, so
    // gating on GENERATING alone would drop those closing events. Only stray events while IDLE are
    // ignored, unless a vibe tool is just starting (which itself advances IDLE -> GENERATING).
    if (s.phase == DjAiPhase.IDLE && !startsVibeRun) return s
    return when (e) {
        is AgentActivityEvent.Reasoning -> {
            val (newFeed, newNextId) = appendReasoning(s.feed, s.nextId, e.text)
            s.copy(feed = newFeed, nextId = newNextId)
        }
        is AgentActivityEvent.ToolStarted -> {
            val newPhase = if (s.phase == DjAiPhase.IDLE && e.tool in VIBE_TOOLS) {
                DjAiPhase.GENERATING
            } else {
                s.phase
            }
            s.copy(
                phase = newPhase,
                feed = s.feed + DjAiFeedItem.Tool(id = s.nextId, name = friendly(e.tool), running = true),
                nextId = s.nextId + 1,
            )
        }
        is AgentActivityEvent.ToolCompleted -> {
            // Consolidate: flip this tool's in-flight running row to done in place rather
            // than appending a second row for the same call. Falls back to appending if the
            // start row was never recorded (e.g. its event predated GENERATING).
            val name = friendly(e.tool)
            val feedIdx = s.feed.indexOfLast {
                it is DjAiFeedItem.Tool && it.name == name && it.running
            }
            s.copy(
                feed = if (feedIdx >= 0) {
                    s.feed.toMutableList().also {
                        it[feedIdx] = (it[feedIdx] as DjAiFeedItem.Tool).copy(running = false)
                    }
                } else {
                    s.feed + DjAiFeedItem.Tool(id = s.nextId, name = name, running = false)
                },
                nextId = if (feedIdx >= 0) s.nextId else s.nextId + 1,
            )
        }
        is AgentActivityEvent.Assistant -> {
            // The main conversational reply is the feed's trailing Reply row. Latest wins:
            // a consecutive Assistant event updates the tail in place (id preserved).
            val tail = s.feed.lastOrNull()
            if (tail is DjAiFeedItem.Reply) {
                s.copy(
                    feed = s.feed.dropLast(1) + tail.copy(text = e.text),
                )
            } else {
                s.copy(
                    feed = s.feed + DjAiFeedItem.Reply(id = s.nextId, text = e.text),
                    nextId = s.nextId + 1,
                )
            }
        }
        is AgentActivityEvent.Error ->
            // Agent/LLM failure (e.g. an API 400): end the run visibly — back to the prompt
            // with the error banner. The feed keeps its failure context.
            s.copy(phase = DjAiPhase.IDLE, error = e.message)
    }
}

/**
 * Reduce a [VibeCreateEvent] into a new [DjAiUiState].
 */
internal fun reduceVibeEvent(e: VibeCreateEvent, s: DjAiUiState): DjAiUiState = when (e) {
    is VibeCreateEvent.Generating -> s.copy(phase = DjAiPhase.GENERATING, error = null)
    is VibeCreateEvent.Generated -> s.copy(phase = DjAiPhase.RESULT, error = null)
    is VibeCreateEvent.Failed -> s.copy(phase = DjAiPhase.IDLE, error = e.error)
}

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the DJ AI vibe-creation panel.
 *
 * Wires [AgentActivityEventBus] and [VibeCreateEventBus] into [DjAiUiState] via
 * pure reducers, and delegates key / model management to the shared repositories.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(DjAiFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<DjAiFeature>())
class DjAiViewModel(
    private val agent: OrpheusAgent,
    private val activityEventBus: AgentActivityEventBus,
    private val vibeEventBus: VibeCreateEventBus,
    private val aiKeyRepository: AiKeyRepository,
    private val aiModelProvider: AiModelProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val playbackController: PlaybackController,
    private val scope: FeatureCoroutineScope,
) : DjAiFeature {

    private val log = logging("DjAiViewModel")

    private val _uiState = MutableStateFlow(
        DjAiUiState(
            selectedModel = aiModelProvider.selectedModel.value,
            availableModels = aiModelProvider.availableModels,
        )
    )

    override val stateFlow: StateFlow<DjAiUiState> = _uiState.asStateFlow()

    init {
        // Collect agent activity events -> reduceActivityEvent
        scope.launch(dispatcherProvider.default) {
            activityEventBus.events.collect { event ->
                _uiState.update { s -> reduceActivityEvent(event, s) }
            }
        }

        // Collect vibe-create events -> reduceVibeEvent
        scope.launch(dispatcherProvider.default) {
            vibeEventBus.events.collect { event ->
                // Capture activity BEFORE reducing: a dismiss/reset drops phase back to IDLE, and a
                // late Generated must NOT auto-start audio from silence in that case.
                val wasActive = _uiState.value.phase != DjAiPhase.IDLE
                _uiState.update { s -> reduceVibeEvent(event, s) }
                // A generated vibe is applied to the engine, but in the DJ app the transport is
                // EXPLICIT — playback is gated by PlaybackController (unlike the Orpheus app where
                // Pulsar always runs), so a vibe applied while stopped loads silently. Start the
                // transport so "play X" actually produces sound. play() is idempotent (no-op if
                // already Playing) and must run on main (media session / audio focus), matching the
                // manual transport toggle in MainActivity/main.kt.
                if (event is VibeCreateEvent.Generated && wasActive) {
                    withContext(dispatcherProvider.main) { playbackController.play() }
                }
            }
        }

        // Track selected model
        scope.launch(dispatcherProvider.default) {
            aiModelProvider.selectedModel.collect { model ->
                _uiState.update { it.copy(selectedModel = model) }
            }
        }

        // Track whether an API key is configured for the SELECTED provider.
        // isApiKeySetFlow is a global last-write flag (not provider-scoped), so it is used here
        // only as a change-trigger; its value is ignored. The actual check is the pure, read-only
        // AiKeyRepository.hasKey(provider), which cannot cause a feedback loop.
        scope.launch(dispatcherProvider.default) {
            combine(
                aiModelProvider.selectedModel,
                aiKeyRepository.isApiKeySetFlow,
            ) { model, _ -> model.aiProvider }
                .collect { provider ->
                    val hasKey = aiKeyRepository.hasKey(provider)
                    _uiState.update { it.copy(isKeySet = hasKey) }
                }
        }
    }

    // ─────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────

    private fun updateDraft(text: String) {
        _uiState.update { it.copy(promptDraft = text) }
    }

    private fun submit() {
        val current = _uiState.value
        if (current.phase == DjAiPhase.GENERATING) return
        val draft = current.promptDraft.trim()
        if (draft.isBlank()) return

        log.debug { "Submitting prompt: $draft" }
        _uiState.update { startRun(it, draft) }
        agent.sendPrompt(PromptIntent(
            prompt = "Make a Pulsar vibe: $draft",
            displayText = draft,
        ))
    }

    private fun saveKey(aiProvider: AiProvider, key: String) {
        scope.launch(dispatcherProvider.io) {
            val success = aiKeyRepository.setKey(aiProvider, key)
            if (success) {
                log.debug { "API key saved successfully" }
            } else {
                log.warn { "Failed to save API key" }
            }
        }
    }

    private fun clearKey(aiProvider: AiProvider) {
        scope.launch(dispatcherProvider.io) {
            aiKeyRepository.clearApiKey(aiProvider)
            log.debug { "API key cleared" }
        }
    }

    private fun selectModel(model: AiModel) {
        scope.launch(dispatcherProvider.default) {
            aiModelProvider.selectModel(model)
            // The agent pins its model at run() creation, so a live selection would otherwise not
            // take effect. restart() is SILENT for the DJ app (cancel + re-arm, no greeting), which
            // re-reads config.model on the next submit with zero LLM traffic.
            agent.restart()
            _uiState.update { applyModelSwitch(it) }
            log.debug { "Model selected: ${model.displayName}" }
        }
    }

    /**
     * Cancel an in-flight generation (or clear a stale result/error) and return to IDLE.
     *
     * Calls [OrpheusAgent.restart] to abort the current Koog run before clearing UI state. For the
     * DJ app, restart() is a SILENT cancel + re-arm (no greeting), so this aborts the in-flight run
     * on an explicit user-initiated cancel / panel dismiss with zero LLM traffic.
     */
    private fun reset() {
        agent.restart()
        _uiState.update { clearRunOutput(it).copy(phase = DjAiPhase.IDLE) }
    }

    /** Clear just the error banner — the feed keeps its failure context. */
    private fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override val actions = DjAiPanelActions(
        updateDraft = ::updateDraft,
        submit = ::submit,
        saveKey = ::saveKey,
        clearKey = ::clearKey,
        selectModel = ::selectModel,
        reset = ::reset,
        dismissError = ::dismissError,
    )

    companion object {
        fun previewFeature(state: DjAiUiState = DjAiUiState()): DjAiFeature =
            object : DjAiFeature {
                override val stateFlow: StateFlow<DjAiUiState> = MutableStateFlow(state)
                override val actions: DjAiPanelActions = DjAiPanelActions.EMPTY
            }

        @Composable
        fun feature(): DjAiFeature =
            synthFeature<DjAiFeature>()
    }
}
