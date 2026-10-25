package org.balch.orpheus.djapp.ai

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.ideas.Idea
import org.balch.orpheus.djapp.ai.mcp.McpEndpoint
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.features.ai.usage.AiTurnUsage

/** Lifecycle phase of the DJ AI chat. */
enum class DjAiPhase {
    /** No turn running: waiting for user input. */
    IDLE,

    /** The agent's turn is running. */
    GENERATING,

    /** The turn applied a vibe and has not replied yet. */
    RESULT,
}

/** What the AI sheet shows: the chat alone, or the Mixer beside it. */
enum class AiSheetMode { CHAT, MIXER }

/** The question an [DjAiFeedItem.Action] card asks the user. */
enum class ActionKind { OPEN_MIXER, CONFIRM_WIPE }

/**
 * One row of the unified agent feed, in chronological event order.
 *
 * [id] is a monotonically assigned key, unique within a run. The UI uses it as the
 * LazyColumn item key and as the expansion key for [Thinking] rows; reducers preserve
 * it across in-place edits (thinking text accumulation, tool completion, reply update).
 */
@Immutable
sealed interface DjAiFeedItem {
    val id: Long

    /**
     * A message the user sent, one per turn (queued messages join into one).
     *
     * @property text The raw prompt as typed (never the internal agent-prompt prefix).
     */
    data class Request(
        override val id: Long,
        val text: String,
    ) : DjAiFeedItem

    /**
     * A reasoning segment, expandable in the UI.
     *
     * @property headline Bold `**headline**` label parsed from the reasoning stream;
     *   null renders as "Thinking…". [text] never contains its own headline markers.
     * @property text Accumulated raw reasoning for this segment.
     */
    data class Thinking(
        override val id: Long,
        val headline: String?,
        val text: String,
    ) : DjAiFeedItem

    /**
     * A tool invocation row: `🔧 name…` while [running], `✓ name` once complete.
     *
     * @property name Human-readable tool name (already `friendly()`-mapped).
     * @property summary The call in one line, e.g. "mix_set BASS −3.0 dB"; null shows [name].
     */
    data class Tool(
        override val id: Long,
        val name: String,
        val running: Boolean,
        val summary: String? = null,
    ) : DjAiFeedItem

    /** The agent's reply, which ends its turn; [usage] is what that turn spent, when known. */
    data class Reply(
        override val id: Long,
        val text: String,
        val usage: AiTurnUsage? = null,
    ) : DjAiFeedItem

    /** A mixer move by [origin], e.g. "BASS level" from [before] to [after]; a drag updates one row in place. */
    data class Move(
        override val id: Long,
        val origin: EditOrigin,
        val label: String,
        val before: String,
        val after: String,
    ) : DjAiFeedItem

    /** A one-line note from the app, e.g. "Stopped." */
    data class Notice(
        override val id: Long,
        val text: String,
    ) : DjAiFeedItem

    /** A question with accept and decline buttons; [answer] is true for accept, false for decline, null until answered. */
    data class Action(
        override val id: Long,
        val kind: ActionKind,
        val text: String,
        val answer: Boolean? = null,
    ) : DjAiFeedItem {
        val resolved: Boolean get() = answer != null
    }

    /** Marks where a new conversation starts, e.g. after a model switch. */
    data class Divider(
        override val id: Long,
        val text: String,
    ) : DjAiFeedItem
}

/** The song page attached to the chat; the agent reads it by [id] through `song_reference`. */
@Immutable
data class SongPasteRef(val id: String, val lines: Int, val title: String?)

/**
 * UI state for the DJ AI chat.
 *
 * @property phase Current lifecycle phase.
 * @property promptDraft The user's in-progress text input.
 * @property lastPrompt Raw text of the most recently submitted prompt. Set on submit,
 *   never cleared (survives reset/error/model switch); feeds the model-switch prefill.
 * @property feed The conversation, oldest first, kept across turns until a new conversation.
 * @property nextId Monotonic id source for [feed] items; reducers stay pure by
 *   threading it through state.
 * @property error Error description set when [phase] == [DjAiPhase.IDLE] after a failure.
 * @property selectedModel Currently selected AI model.
 * @property availableModels All models the user may choose from.
 * @property isKeySet Whether an API key is configured for the current model's provider.
 * @property castMode How a new vibe is cast: bed fixed or everything rolled. Saved across launches.
 * @property reference The attached song reference, kept across follow-up runs until detached.
 * @property parsing Ticket of a paste still parsing into [reference]; null when none is.
 * @property mcpEndpoint The loopback MCP server's url and token; null until it listens (and on iOS).
 * @property sheetMode Chat alone or the Mixer beside it.
 * @property queued Messages sent while a turn runs; they go as one prompt when it ends.
 * @property ideas The ideas strip's cards.
 * @property mixerVisible Whether the Mixer is on screen, so the agent may change the mix.
 * @property pins Track indices the user pinned.
 * @property recapNext The next prompt opens with a recap: a cancel or an error took the agent's history.
 * @property confirmWipeNext The next turn carries the user's confirmation to clear their mix.
 * @property referenceSentId The song reference the agent was told about in this conversation; another is told on the next send.
 */
@Immutable
data class DjAiUiState(
    val phase: DjAiPhase = DjAiPhase.IDLE,
    val promptDraft: String = "",
    val lastPrompt: String = "",
    val feed: List<DjAiFeedItem> = emptyList(),
    val nextId: Long = 0,
    val error: String? = null,
    val selectedModel: AiModel = AiModel.DEFAULT,
    val availableModels: List<AiModel> = emptyList(),
    val isKeySet: Boolean = false,
    val mcpEndpoint: McpEndpoint? = null,
    val castMode: CastMode = CastMode.BED_FIXED,
    val reference: SongPasteRef? = null,
    val parsing: Long? = null,
    val sheetMode: AiSheetMode = AiSheetMode.CHAT,
    val queued: List<String> = emptyList(),
    val ideas: List<Idea> = emptyList(),
    val mixerVisible: Boolean = false,
    val pins: Set<Int> = emptySet(),
    internal val recapNext: Boolean = false,
    internal val confirmWipeNext: Boolean = false,
    internal val referenceSentId: String? = null,
)

/**
 * Actions available from the DJ AI chat.
 */
data class DjAiPanelActions(
    /** Update the prompt draft text. */
    val updateDraft: (String) -> Unit,
    /** Send the current draft, or queue it while a turn runs. */
    val submit: () -> Unit,
    /** Persist a user-provided API key. */
    val saveKey: (AiProvider, String) -> Unit,
    /** Remove the stored API key for the given provider. */
    val clearKey: (AiProvider) -> Unit,
    /** Switch the active AI model. */
    val selectModel: (AiModel) -> Unit,
    /** Choose how a new vibe is cast; saved. */
    val selectCastMode: (CastMode) -> Unit,
    /** Stop the running turn; the conversation stays. */
    val cancel: () -> Unit,
    /** Clear the feed and start a new conversation. */
    val newConversation: () -> Unit,
    /** Dismiss the error banner without touching the feed's failure context. */
    val dismissError: () -> Unit,
    /** Attach a big paste as the song reference, replacing any earlier one. */
    val attachReference: (String) -> Unit,
    /** Drop the attached song reference. */
    val detachReference: () -> Unit,
    /** Answer the [DjAiFeedItem.Action] card [id]. */
    val onAction: (id: Long, accept: Boolean) -> Unit,
    val setSheetMode: (AiSheetMode) -> Unit,
    /** Whether the Mixer is on screen now. */
    val setMixerVisible: (Boolean) -> Unit,
    /** Whether the AI sheet is on screen now, in either mode; the user's moves feed the ideas only while it is. */
    val setSheetOpen: (Boolean) -> Unit,
    /** Send an idea's prompt as the next message. */
    val tryIdea: (Idea) -> Unit,
) {
    companion object {
        val EMPTY = DjAiPanelActions(
            updateDraft = {},
            submit = {},
            saveKey = { _, _ -> },
            clearKey = {},
            selectModel = {},
            selectCastMode = {},
            cancel = {},
            newConversation = {},
            dismissError = {},
            attachReference = {},
            detachReference = {},
            onAction = { _, _ -> },
            setSheetMode = {},
            setMixerVisible = {},
            setSheetOpen = {},
            tryIdea = {},
        )
    }
}

/** Feature contract for the DJ AI chat. */
interface DjAiFeature : SynthFeature<DjAiUiState, DjAiPanelActions> {
    // The DJ AI panel has no synth-control ports of its own.
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}
