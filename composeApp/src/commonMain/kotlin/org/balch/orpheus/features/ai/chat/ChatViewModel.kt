package org.balch.orpheus.features.ai.chat

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.features.ai.OrpheusAgent
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.session.SessionUsage
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.utils.PanelViewModel

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

/**
 * ViewModel for the AI chat panel.
 */
@Inject
@ViewModelKey(ChatViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ChatViewModel(
    private val agent: OrpheusAgent,
) : ViewModel(), PanelViewModel<ChatUiState, ChatPanelActions> {

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
            started = SharingStarted.WhileSubscribed(5000),
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
        .map { it is OrpheusAgent.AgentState.Loading || it.messages.lastOrNull()?.type == ChatMessageType.Loading }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /**
     * Send a prompt to the agent.
     */
    override val panelActions = ChatPanelActions(
        onSendPrompt = ::sendPrompt
    )

    override val uiState: StateFlow<ChatUiState> = combine(
        messages,
        isLoading
    ) { msgs, loading ->
        ChatUiState(
            messages = msgs,
            isLoading = loading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    fun sendPrompt(message: String) {
        agent.sendPrompt(message)
    }
}

