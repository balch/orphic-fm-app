package org.balch.orpheus.djapp.ai

import androidx.compose.runtime.Composable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
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

/** Prefix marking an Activity row that came from a Thinking-stream headline (vs a 🔧/✓ tool step). */
internal const val HEADLINE_PREFIX = "💭 "

private val HEADLINE_RE = Regex("""\*\*(.+?)\*\*""")

/**
 * Pull the `**bold**` headlines out of a reasoning blob. The model prefixes each reasoning
 * section with a short bold title (e.g. `**Defining the Key and Tempo**`); those make a clean
 * high-level narrative for the Activity feed. Surrounding `*`/`:`/whitespace is trimmed.
 */
internal fun extractHeadlines(text: String): List<String> =
    HEADLINE_RE.findAll(text)
        .map { it.groupValues[1].trim().trim('*', ':', ' ') }
        .filter { it.isNotBlank() }
        .toList()

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
 * - [AgentActivityEvent.Reasoning] -> appended to [DjAiUiState.thinking]; any `**bold**` headlines
 *   in the accumulated reasoning are also mirrored into [DjAiUiState.activity] (deduped) as a
 *   `💭`-prefixed high-level narrative.
 * - [AgentActivityEvent.Assistant] -> the agent's main reply -> [DjAiUiState.assistantReply]
 *   (latest wins), surfaced in the prompt-row status card, NOT buried in the activity log.
 * - [AgentActivityEvent.ToolStarted]/[AgentActivityEvent.ToolCompleted] -> [DjAiUiState.activity].
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
            val newThinking = s.thinking + e.text
            // Mirror the reasoning's bold headlines into Activity. Parse from the FULL accumulated
            // thinking (a headline can arrive split across streamed chunks) and add only ones not
            // already shown.
            val existing = s.activity
                .filter { it.startsWith(HEADLINE_PREFIX) }
                .map { it.removePrefix(HEADLINE_PREFIX) }
                .toSet()
            val fresh = extractHeadlines(newThinking.joinToString("\n"))
                .distinct()
                .filter { it !in existing }
                .map { HEADLINE_PREFIX + it }
            s.copy(thinking = newThinking, activity = s.activity + fresh)
        }
        is AgentActivityEvent.ToolStarted -> {
            val newPhase = if (s.phase == DjAiPhase.IDLE && e.tool in VIBE_TOOLS) {
                DjAiPhase.GENERATING
            } else {
                s.phase
            }
            s.copy(
                phase = newPhase,
                activity = s.activity + "🔧 ${friendly(e.tool)}…",
            )
        }
        is AgentActivityEvent.ToolCompleted -> {
            // Consolidate: flip this tool's in-flight "🔧 name…" row to "✓ name" in place
            // rather than appending a second row for the same call. Falls back to appending
            // if the start row was never recorded (e.g. its event predated GENERATING).
            val running = "🔧 ${friendly(e.tool)}…"
            val done = "✓ ${friendly(e.tool)}"
            val idx = s.activity.lastIndexOf(running)
            s.copy(
                activity = if (idx >= 0) {
                    s.activity.toMutableList().also { it[idx] = done }
                } else {
                    s.activity + done
                },
            )
        }
        is AgentActivityEvent.Assistant ->
            // The main conversational reply is surfaced in the prompt-row status card, not the
            // activity log where it used to scroll away. Latest message wins.
            s.copy(assistantReply = e.text)
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
@ClassKey
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
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
        _uiState.update { it.copy(
            phase = DjAiPhase.GENERATING,
            promptDraft = "",
            activity = emptyList(),
            thinking = emptyList(),
            assistantReply = "",
            error = null,
        ) }
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
        _uiState.update { it.copy(
            phase = DjAiPhase.IDLE,
            activity = emptyList(),
            thinking = emptyList(),
            assistantReply = "",
            error = null,
        ) }
    }

    /** Clear just the error banner — activity/thinking keep their failure context. */
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
            synthFeature<DjAiViewModel, DjAiFeature>()
    }
}
