package org.balch.orpheus.djapp.ai

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.features.SynthFeature

/** Lifecycle phase of the DJ AI vibe-creation flow. */
enum class DjAiPhase {
    /** No active request — waiting for user input. */
    IDLE,

    /** Agent is running — generating a vibe. */
    GENERATING,

    /** A vibe was produced and is live. */
    RESULT,
}

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
     */
    data class Tool(
        override val id: Long,
        val name: String,
        val running: Boolean,
    ) : DjAiFeedItem

    /** The agent's conversational reply, shown as the feed's trailing row. */
    data class Reply(
        override val id: Long,
        val text: String,
    ) : DjAiFeedItem
}

/**
 * UI state for the DJ AI vibe-creation panel.
 *
 * @property phase Current lifecycle phase.
 * @property promptDraft The user's in-progress text input.
 * @property feed Unified chronological agent feed: expandable [DjAiFeedItem.Thinking]
 *   segments, [DjAiFeedItem.Tool] rows, and the trailing [DjAiFeedItem.Reply].
 * @property nextId Monotonic id source for [feed] items; reducers stay pure by
 *   threading it through state.
 * @property error Error description set when [phase] == [DjAiPhase.IDLE] after a failure.
 * @property selectedModel Currently selected AI model.
 * @property availableModels All models the user may choose from.
 * @property isKeySet Whether an API key is configured for the current model's provider.
 */
@Immutable
data class DjAiUiState(
    val phase: DjAiPhase = DjAiPhase.IDLE,
    val promptDraft: String = "",
    val feed: List<DjAiFeedItem> = emptyList(),
    val nextId: Long = 0,
    val error: String? = null,
    val selectedModel: AiModel = AiModel.DEFAULT,
    val availableModels: List<AiModel> = emptyList(),
    val isKeySet: Boolean = false,
)

/**
 * Actions available from the DJ AI vibe-creation panel.
 */
data class DjAiPanelActions(
    /** Update the prompt draft text. */
    val updateDraft: (String) -> Unit,
    /** Submit the current draft to the agent. */
    val submit: () -> Unit,
    /** Persist a user-provided API key. */
    val saveKey: (AiProvider, String) -> Unit,
    /** Remove the stored API key for the given provider. */
    val clearKey: (AiProvider) -> Unit,
    /** Switch the active AI model. */
    val selectModel: (AiModel) -> Unit,
    /** Reset to IDLE, clearing the feed and error. */
    val reset: () -> Unit,
    /** Dismiss the error banner without touching the feed's failure context. */
    val dismissError: () -> Unit,
) {
    companion object {
        val EMPTY = DjAiPanelActions(
            updateDraft = {},
            submit = {},
            saveKey = { _, _ -> },
            clearKey = {},
            selectModel = {},
            reset = {},
            dismissError = {},
        )
    }
}

/** Feature contract for the DJ AI vibe-creation panel. */
interface DjAiFeature : SynthFeature<DjAiUiState, DjAiPanelActions> {
    // The DJ AI panel has no synth-control ports of its own.
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}
