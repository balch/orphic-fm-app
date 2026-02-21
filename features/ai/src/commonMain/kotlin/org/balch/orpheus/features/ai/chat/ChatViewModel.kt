package org.balch.orpheus.features.ai.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.features.ai.AgentState
import org.balch.orpheus.features.ai.OrpheusAgent
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.ui.theme.OrpheusColors

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val accentColor: Color = OrpheusColors.warmGlow
)

data class ChatPanelActions(
    val onSendPrompt: (String) -> Unit
) {
    companion object {
        val EMPTY = ChatPanelActions(
            onSendPrompt = {},
        )
    }
}

interface ChatFeature : SynthFeature<ChatUiState, ChatPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

/**
 * ViewModel for the AI chat panel.
 */
@Inject
@ClassKey(ChatViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class ChatViewModel(
    private val agent: OrpheusAgent,
    private val scope: FeatureCoroutineScope,
) : ChatFeature {

    /**
     * Chat messages flow.
     */
    val messages: StateFlow<List<ChatMessage>> = agent.agentFlow
        .map { it.messages }
        .stateIn(
            scope = scope,
            started = this.sharingStrategy,
            initialValue = emptyList()
        )

    /**
     * Whether the agent is currently processing.
     */
    val isLoading: StateFlow<Boolean> = agent.agentFlow
        .map { it is AgentState.Loading || it.messages.lastOrNull()?.type == ChatMessageType.Loading }
        .stateIn(
            scope = scope,
            started = this.sharingStrategy,
            initialValue = true
        )

    /**
     * Send a prompt to the agent.
     */
    override val actions = ChatPanelActions(
        onSendPrompt = ::sendPrompt
    )

    override val stateFlow: StateFlow<ChatUiState> = combine(
        messages,
        isLoading
    ) { msgs, loading ->
        ChatUiState(
            messages = msgs,
            isLoading = loading
        )
    }.stateIn(
        scope = scope,
        started = this.sharingStrategy,
        initialValue = ChatUiState()
    )

    fun sendPrompt(message: String) {
        agent.sendPrompt(message)
    }

    companion object {
        fun previewFeature(state: ChatUiState = ChatUiState()): ChatFeature =
            object : ChatFeature {
                override val stateFlow: StateFlow<ChatUiState> = MutableStateFlow(state)
                override val actions: ChatPanelActions = ChatPanelActions.EMPTY
            }

        @Composable
        fun feature(): ChatFeature =
            synthFeature<ChatViewModel, ChatFeature>()
    }
}

