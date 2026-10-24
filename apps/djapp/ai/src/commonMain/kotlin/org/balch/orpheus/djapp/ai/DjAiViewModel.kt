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
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.djapp.ai.collab.CollabEvents
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixMove
import org.balch.orpheus.djapp.ai.ideas.Idea
import org.balch.orpheus.djapp.ai.ideas.IdeasGenerator
import org.balch.orpheus.djapp.ai.ideas.IdeasInput
import org.balch.orpheus.djapp.ai.ideas.IdeasScheduler
import org.balch.orpheus.djapp.ai.ideas.mixSnapshot
import org.balch.orpheus.djapp.ai.ideas.recentChat
import org.balch.orpheus.djapp.ai.ideas.recentMoves
import org.balch.orpheus.djapp.ai.ideas.vibeSummary
import org.balch.orpheus.djapp.ai.mcp.McpEndpoint
import org.balch.orpheus.djapp.ai.mcp.McpEndpointState
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.AgentActivityEventBus
import org.balch.orpheus.features.ai.OrpheusAgent
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.VibeCreateEventBus
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import kotlin.time.TimeSource

// ─────────────────────────────────────────────────────────────────────────
// Pure reducers (internal so tests can call them directly without DI)
// ─────────────────────────────────────────────────────────────────────────

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

/** The clear for a new conversation: an empty feed and no error. */
internal fun clearRunOutput(s: DjAiUiState): DjAiUiState =
    s.copy(feed = emptyList(), nextId = 0, error = null)

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
    "song_reference" -> "Song Reference"
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
 * - Events while [DjAiPhase.IDLE] are strays from a stopped run and are dropped: only [DjAiChat] starts a turn.
 */
internal fun reduceActivityEvent(e: AgentActivityEvent, s: DjAiUiState): DjAiUiState {
    // Process events in both GENERATING and RESULT: VibeApplyTool emits VibeCreateEvent.Generated
    // (flipping GENERATING -> RESULT) BEFORE Koog fires the trailing ToolCompleted + Assistant, so
    // gating on GENERATING alone would drop those closing events.
    if (s.phase == DjAiPhase.IDLE) return s
    return when (e) {
        is AgentActivityEvent.Reasoning -> {
            val (newFeed, newNextId) = appendReasoning(s.feed, s.nextId, e.text)
            s.copy(feed = newFeed, nextId = newNextId)
        }
        is AgentActivityEvent.ToolStarted -> {
            s.copy(
                feed = s.feed + DjAiFeedItem.Tool(
                    id = s.nextId,
                    name = friendly(e.tool),
                    running = true,
                    summary = toolSummary(e.tool, e.args),
                ),
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
 * A mixer move joins the chat; a USER move starts the ideas pause only while the AI sheet is open. Moves from the
 * small Mix panel, the Pulsar knobs or MIDI with the sheet closed cost no ideas call.
 */
internal fun onMixMove(move: MixMove, sheetOpen: Boolean, chat: DjAiChat, ideas: IdeasScheduler, input: () -> IdeasInput) {
    chat.onMove(move)
    if (move.origin == EditOrigin.USER && sheetOpen) ideas.onUserMove(input)
}

/** Carry the MCP server's current endpoint (null while it is not listening) into the UI state. */
internal fun withMcpEndpoint(s: DjAiUiState, endpoint: McpEndpoint?): DjAiUiState =
    s.copy(mcpEndpoint = endpoint)

/** The saved casting mode, falling back to [CastMode.BED_FIXED] for a missing or unknown value. */
internal fun castModeFromPref(saved: String?): CastMode =
    CastMode.entries.firstOrNull { it.name == saved } ?: CastMode.BED_FIXED

internal fun withCastMode(s: DjAiUiState, mode: CastMode): DjAiUiState = s.copy(castMode = mode)

/** The loaded preference, only while the UI still shows the default: a tap that beat the load wins. */
internal fun withSavedCastMode(s: DjAiUiState, saved: CastMode): DjAiUiState =
    if (s.castMode == DjAiUiState().castMode) s.copy(castMode = saved) else s

/**
 * Reduce a [VibeCreateEvent] into a new [DjAiUiState]. Only an in-app turn's events count: an apply made while
 * IDLE (an MCP call) leaves the chat alone.
 */
internal fun reduceVibeEvent(e: VibeCreateEvent, s: DjAiUiState): DjAiUiState = if (s.phase == DjAiPhase.IDLE) s else when (e) {
    is VibeCreateEvent.Generating -> s.copy(phase = DjAiPhase.GENERATING, error = null)
    is VibeCreateEvent.Generated -> s.copy(phase = DjAiPhase.RESULT, error = null)
    // The agent fixes the JSON and retries in the same turn, so a failed apply does not end it.
    is VibeCreateEvent.Failed -> s.copy(error = e.error)
}

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the DJ AI chat.
 *
 * Wires [AgentActivityEventBus], [VibeCreateEventBus], the mixer's moves and the tools' questions into
 * [DjAiUiState] through [DjAiChat] and pure reducers, and delegates key / model management to the shared repositories.
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
    private val mcpEndpointState: McpEndpointState,
    private val preferences: AppPreferencesRepository,
    private val songPasteStore: SongPasteStore,
    private val collab: MixCollab,
    private val collabEvents: CollabEvents,
    ideasGenerator: IdeasGenerator,
    // Lazy providers, as in MixReadTool: the feature and the AppScope controller would close a DI cycle.
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val mixBenchProvider: () -> MixBenchController,
) : DjAiFeature {

    private val log = logging("DjAiViewModel")

    private val _uiState = MutableStateFlow(
        DjAiUiState(
            selectedModel = aiModelProvider.selectedModel.value,
            availableModels = aiModelProvider.availableModels,
        )
    )

    override val stateFlow: StateFlow<DjAiUiState> = _uiState.asStateFlow()

    private val clockStart = TimeSource.Monotonic.markNow()

    private val ideas = IdeasScheduler(scope, ideasGenerator, clock = { clockStart.elapsedNow().inWholeMilliseconds })

    /** Whether the AI sheet is composed, in either mode. */
    private val sheetOpen = MutableStateFlow(false)

    private val chat = DjAiChat(
        _uiState,
        collab,
        scope,
        sendPrompt = { agent.sendPrompt(it) },
        restartAgent = agent::restart,
        onTurnStarted = ideas::onTurnStarted,
        onTurnEnded = { ideas.onTurnEnded(::ideasInput) },
    )

    init {
        scope.launch(dispatcherProvider.default) {
            activityEventBus.events.collect { chat.onActivity(it) }
        }

        scope.launch(dispatcherProvider.default) {
            collab.moves.collect { onMixMove(it, sheetOpen.value, chat, ideas, ::ideasInput) }
        }

        // Mix ideas show only while the Mixer is open.
        scope.launch(dispatcherProvider.default) {
            combine(ideas.ideas, collab.mixerVisible) { list, open -> if (open) list else list.filterNot { it.mix } }
                .collect { list -> _uiState.update { it.copy(ideas = list) } }
        }

        // CollabEvents has no replay, so it is collected here for the feature's life, never from a composable.
        scope.launch(dispatcherProvider.default) {
            collabEvents.events.collect { chat.onCollabEvent(it) }
        }

        scope.launch(dispatcherProvider.default) {
            combine(collab.mixerVisible, collab.pins) { v, p -> v to p }.collect { (visible, pins) ->
                _uiState.update { it.copy(mixerVisible = visible, pins = pins) }
            }
        }

        // Collect vibe-create events -> reduceVibeEvent
        scope.launch(dispatcherProvider.default) {
            vibeEventBus.events.collect { event ->
                val play = chat.onVibeEvent(event)
                // A generated vibe is applied to the engine, but in the DJ app the transport is
                // EXPLICIT — playback is gated by PlaybackController (unlike the Orpheus app where
                // Pulsar always runs), so a vibe applied while stopped loads silently. Start the
                // transport so "play X" actually produces sound. play() is idempotent (no-op if
                // already Playing) and must run on main (media session / audio focus), matching the
                // manual transport toggle in MainActivity/main.kt.
                if (play) {
                    withContext(dispatcherProvider.main) { playbackController.play() }
                }
            }
        }

        scope.launch(dispatcherProvider.default) {
            mcpEndpointState.endpoint.collect { endpoint ->
                _uiState.update { withMcpEndpoint(it, endpoint) }
            }
        }

        scope.launch(dispatcherProvider.io) {
            val saved = castModeFromPref(preferences.load().vibeCastMode)
            _uiState.update { withSavedCastMode(it, saved) }
        }

        SheetModeMemory(_uiState, preferences, scope, dispatcherProvider.io)

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

    /** What the next ideas call sees, read now. */
    private fun ideasInput(): IdeasInput {
        val feed = _uiState.value.feed
        val pulsar = pulsarFeatureProvider()
        val bench = mixBenchProvider()
        val vibe = pulsar.vibeFlow.value
        val section = pulsar.arrangementStateFlow.value.sectionIndex
        return IdeasInput(
            mixerOpen = collab.mixerVisible.value,
            vibeSummary = vibeSummary(vibe, section),
            mixSnapshot = mixSnapshot(
                vibe = vibe,
                section = section,
                layers = bench.layers.value,
                energy = pulsar.macroKnob(Macro.ENERGY),
                muted = bench.muted.value,
                soloed = bench.soloed.value,
                macros = Macro.entries.associate { it.name.lowercase() to pulsar.macroKnob(it) },
            ),
            recentMoves = recentMoves(feed),
            recentChat = recentChat(feed),
        )
    }

    // ─────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────

    private fun updateDraft(text: String) {
        _uiState.update { it.copy(promptDraft = text) }
    }

    private fun submit() {
        val draft = _uiState.value.promptDraft.trim()
        // An Enter right after a big paste waits for its chip, so the prompt carries the pasteId.
        references.whenAttached { chat.send(draft, _uiState.value.reference, clearDraft = true) }
    }

    private fun tryIdea(idea: Idea) = chat.send(idea.prompt, _uiState.value.reference, label = idea.text)

    private val references = ReferenceAttacher(songPasteStore, scope, dispatcherProvider.default, _uiState)

    private fun attachReference(text: String) = references.attach(text)

    private fun detachReference() = references.detach()

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
            chat.switchModel(model.displayName)
            log.debug { "Model selected: ${model.displayName}" }
        }
    }

    private fun selectCastMode(mode: CastMode) {
        _uiState.update { withCastMode(it, mode) }
        scope.launch(dispatcherProvider.io) {
            preferences.update { it.copy(vibeCastMode = mode.name) }
        }
    }

    private fun setSheetMode(mode: AiSheetMode) {
        _uiState.update { it.copy(sheetMode = mode) }
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
        selectCastMode = ::selectCastMode,
        cancel = chat::cancel,
        newConversation = chat::newConversation,
        dismissError = ::dismissError,
        attachReference = ::attachReference,
        detachReference = ::detachReference,
        onAction = chat::onAction,
        setSheetMode = ::setSheetMode,
        setMixerVisible = collab::setMixerVisible,
        setSheetOpen = { sheetOpen.value = it },
        tryIdea = ::tryIdea,
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
