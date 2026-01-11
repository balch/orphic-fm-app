package org.balch.orpheus.features.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.session.SessionUsage

@SingleIn(AppScope::class)
actual class OrpheusAgent @Inject constructor() {
    actual val sessionUsage: StateFlow<SessionUsage> = MutableStateFlow(SessionUsage.EMPTY).asStateFlow()
    actual val statusMessages: SharedFlow<AiStatusMessage> = MutableSharedFlow<AiStatusMessage>().asSharedFlow()
    actual val isApiKeySet: Boolean = false
    actual val agentFlow: StateFlow<AgentState> = MutableStateFlow<AgentState>(AgentState.Chatting(emptyList())).asStateFlow()

    actual fun sendPrompt(prompt: PromptIntent) {}
    actual fun sendReplPrompt(
        displayText: String,
        selectedMood: String,
        selectedMode: String,
        selectedKey: String,
    ) {}
    actual fun sendPrompt(text: String) {}
    actual fun restart() {}
    actual fun addExternalMessage(text: String, type: ChatMessageType) {}
}
