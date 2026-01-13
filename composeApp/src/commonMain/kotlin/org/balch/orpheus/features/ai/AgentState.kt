package org.balch.orpheus.features.ai

import org.balch.orpheus.features.ai.chat.widgets.ChatMessage

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
