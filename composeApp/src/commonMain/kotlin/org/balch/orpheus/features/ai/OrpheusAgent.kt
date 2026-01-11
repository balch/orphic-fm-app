package org.balch.orpheus.features.ai

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.session.SessionUsage

/**
 * Intent for sending a prompt to the agent.
 */
data class PromptIntent(
    val prompt: String,
    val displayText: String = prompt,
)

/**
 * Orpheus AI Agent - a musical guide inhabiting the Orphic-FM synthesizer.
 * Uses Gemini to provide expert advice on sounds and can control the synth.
 */
expect class OrpheusAgent {
    val sessionUsage: StateFlow<SessionUsage>
    val statusMessages: SharedFlow<AiStatusMessage>
    val isApiKeySet: Boolean
    val agentFlow: StateFlow<AgentState>

    fun sendPrompt(prompt: PromptIntent)
    fun sendReplPrompt(
        displayText: String,
        selectedMood: String,
        selectedMode: String,
        selectedKey: String,
    )
    fun sendPrompt(text: String)
    fun restart()
    fun addExternalMessage(text: String, type: ChatMessageType = ChatMessageType.Agent)
}

/**
 * Agent state sealed interface.
 */
sealed interface AgentState {
    val messages: List<ChatMessage>

    data class Chatting(override val messages: List<ChatMessage>) : AgentState
    data class Error(
        val exception: Throwable,
        override val messages: List<ChatMessage>
    ) : AgentState
    data class Loading(override val messages: List<ChatMessage>) : AgentState
}

