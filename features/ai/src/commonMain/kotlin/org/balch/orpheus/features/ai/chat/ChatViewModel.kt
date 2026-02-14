package org.balch.orpheus.features.ai.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.features.ai.AgentState
import org.balch.orpheus.features.ai.OrpheusAgent
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.session.SessionUsage
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

typealias ChatFeature = SynthFeature<ChatUiState, ChatPanelActions>

/**
 * ViewModel for the AI chat panel.
 */
@Inject
@ViewModelKey(ChatViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ChatViewModel(
    private val agent: OrpheusAgent,
) : ViewModel(), ChatFeature {

    /**
     * Whether the API key is configured.
     */
    val isApiKeySet: Boolean get() = agent.isApiKeySet

    /**
     * The accent color for the panel.
     */
    val accentColor: Color = OrpheusColors.warmGlow

    /**
     * Chat messages flow.
     */
    val messages: StateFlow<List<ChatMessage>> = agent.agentFlow
        .map { it.messages }
        .stateIn(
            scope = viewModelScope,
            started = this.sharingStrategy,
            initialValue = emptyList()
        )

    /**
     * Session usage statistics.
     */
    val sessionUsage: StateFlow<SessionUsage> = agent.sessionUsage

    /**
     * Whether the agent is currently processing.
     */
    val isLoading: StateFlow<Boolean> = agent.agentFlow
        .map { it is AgentState.Loading || it.messages.lastOrNull()?.type == ChatMessageType.Loading }
        .stateIn(
            scope = viewModelScope,
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
        scope = viewModelScope,
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
            synthViewModel<ChatViewModel, ChatFeature>()
    }
}

