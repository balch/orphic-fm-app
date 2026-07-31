package org.balch.orpheus.features.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.VibeCreateEventBus
import org.balch.orpheus.features.pulsar.models.Vibe

enum class VibePhase { IDLE, GENERATING, RESULT }

@Immutable
data class VibeCreateUiState(
    val phase: VibePhase = VibePhase.IDLE,
    val promptDraft: String = "",
    val feed: List<String> = emptyList(),
    val vibe: Vibe? = null,
    val error: String? = null,
)

/** Friendly one-line label for an activity event. */
internal fun activityLine(event: AgentActivityEvent): String = when (event) {
    is AgentActivityEvent.ToolStarted -> "🔧 ${friendlyTool(event.tool)}…"
    is AgentActivityEvent.ToolCompleted -> "✓ ${friendlyTool(event.tool)}"
    is AgentActivityEvent.Assistant -> event.text
    is AgentActivityEvent.Reasoning -> "💭 ${event.text}"
    is AgentActivityEvent.Error -> "⚠ ${event.message}"
}

private fun friendlyTool(tool: String): String = when (tool) {
    "pulsar_get_vibe" -> "reading a template"
    "pulsar_vibe_schema" -> "reading the recipe"
    "pulsar_apply_vibe" -> "building the vibe"
    else -> tool
}

/** Tool names that mean "a vibe is being built" — used to engage this panel for chat-initiated runs. */
internal val VIBE_TOOLS = setOf("pulsar_get_vibe", "pulsar_vibe_schema", "pulsar_apply_vibe")

/**
 * Fold an agent activity event into the panel state.
 *  - A vibe tool starting while idle/showing a result begins a fresh run. This is how a vibe asked for
 *    in the AI chat window engages this panel (onSubmit covers the in-panel path): flip to GENERATING
 *    and seed the feed so the agent's thinking is visible from the top.
 *  - Otherwise append activity (tool calls, reasoning, any assistant narration) to the feed while
 *    generating so the user can watch the agent work; ignore activity when idle (shared agent, focused
 *    panel). The phase leaves GENERATING ONLY via the vibe bus (Generated -> RESULT, Failed -> IDLE),
 *    an [AgentActivityEvent.Error] (an LLM/API failure that would otherwise never reach the vibe bus
 *    and leave the panel spinning), or the panel's cancel control — never off an assistant message.
 *    An assistant message mid-run is just narration; resetting on it snaps the panel shut and reads
 *    as a spurious cancel.
 */
internal fun reduceActivity(event: AgentActivityEvent, prev: VibeCreateUiState): VibeCreateUiState {
    val startsVibeRun = event is AgentActivityEvent.ToolStarted && event.tool in VIBE_TOOLS
    return when {
        prev.phase != VibePhase.GENERATING && startsVibeRun ->
            prev.copy(phase = VibePhase.GENERATING, feed = listOf(activityLine(event)), vibe = null, error = null)
        prev.phase != VibePhase.GENERATING -> prev
        event is AgentActivityEvent.Error ->
            prev.copy(phase = VibePhase.IDLE, error = event.message, feed = prev.feed + activityLine(event))
        else -> prev.copy(feed = prev.feed + activityLine(event))
    }
}

internal fun reduceVibeEvent(event: VibeCreateEvent, prev: VibeCreateUiState): VibeCreateUiState = when (event) {
    is VibeCreateEvent.Generating -> prev.copy(phase = VibePhase.GENERATING, error = null)
    is VibeCreateEvent.Failed -> prev.copy(phase = VibePhase.IDLE, error = event.error)
    is VibeCreateEvent.Generated -> prev.copy(phase = VibePhase.RESULT, vibe = event.vibe, error = null)
}

/** One display line per track: "t3  VA  Melodic". */
internal fun instrumentLines(vibe: Vibe): List<String> =
    vibe.tracks.mapIndexed { i, t -> "t$i  ${t.engineEdm.engineId}  ${t.role::class.simpleName}" }

@Inject
@SynthFeatureKey(VibeCreateFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class VibeCreateViewModel(
    private val agent: OrpheusAgent,
    private val vibeEventBus: VibeCreateEventBus,
    private val activityEventBus: AgentActivityEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) : VibeCreateFeature {

    private val log = logging("VibeCreateViewModel")

    private val _uiState = MutableStateFlow(VibeCreateUiState())
    override val stateFlow: StateFlow<VibeCreateUiState> = _uiState.asStateFlow()

    override val actions = VibeCreatePanelActions(
        updateDraft = { draft -> _uiState.update { it.copy(promptDraft = draft) } },
        submit = { onSubmit() },
        reset = { _uiState.update { it.copy(phase = VibePhase.IDLE, feed = emptyList(), vibe = null, error = null) } },
    )

    init {
        scope.launch(dispatcherProvider.default) {
            vibeEventBus.events.collect { event ->
                log.debug { "VibeCreateEvent: $event" }
                _uiState.update { reduceVibeEvent(event, it) }
            }
        }
        scope.launch(dispatcherProvider.default) {
            activityEventBus.events.collect { event ->
                // A vibe asked for in the chat window engages this panel here (no onSubmit fires for
                // that path) — open it on the first vibe tool so the agent's work is visible.
                if (event is AgentActivityEvent.ToolStarted && event.tool in VIBE_TOOLS &&
                    _uiState.value.phase != VibePhase.GENERATING
                ) {
                    panelExpansionEventBus.expand(PanelId.VIBE_CREATE)
                }
                _uiState.update { reduceActivity(event, it) }
            }
        }
    }

    private fun onSubmit() {
        val draft = _uiState.value.promptDraft.trim()
        if (draft.isEmpty()) return
        _uiState.update { it.copy(phase = VibePhase.GENERATING, feed = emptyList(), vibe = null, error = null) }
        agent.sendPrompt(PromptIntent(prompt = "Make a Pulsar vibe: $draft", displayText = draft))
    }

    companion object {
        fun previewFeature(state: VibeCreateUiState = VibeCreateUiState()) =
            object : VibeCreateFeature {
                override val stateFlow: StateFlow<VibeCreateUiState> = MutableStateFlow(state)
                override val actions = VibeCreatePanelActions.EMPTY
            }

        @Composable
        fun feature(): VibeCreateFeature =
            synthFeature<VibeCreateFeature>()
    }
}
