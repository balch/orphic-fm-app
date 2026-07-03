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
 * UI state for the DJ AI vibe-creation panel.
 *
 * @property phase Current lifecycle phase.
 * @property promptDraft The user's in-progress text input.
 * @property activity High-level agent steps (tool starts/completes) and `💭`-prefixed thinking
 *   headlines parsed from the reasoning stream. Auto-scrolling.
 * @property thinking Incremental reasoning / chain-of-thought from the model. User-scrollable.
 * @property assistantReply The agent's main conversational reply (latest wins). Surfaced in the
 *   prompt-row status card while generating / after a result, so it is never lost in the log.
 * @property error Error description set when [phase] == [DjAiPhase.IDLE] after a failure.
 * @property selectedModel Currently selected AI model.
 * @property availableModels All models the user may choose from.
 * @property isKeySet Whether an API key is configured for the current model's provider.
 */
@Immutable
data class DjAiUiState(
    val phase: DjAiPhase = DjAiPhase.IDLE,
    val promptDraft: String = "",
    val activity: List<String> = emptyList(),
    val thinking: List<String> = emptyList(),
    val assistantReply: String = "",
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
    /** Reset to IDLE, clearing activity, thinking, and error. */
    val reset: () -> Unit,
    /** Dismiss the error banner without touching activity/thinking context. */
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
