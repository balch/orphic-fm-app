package org.balch.orpheus.features.ai.generative

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

expect class SynthControlAgent {
    val state: StateFlow<SynthAgentState>
    val statusMessages: SharedFlow<AiStatusMessage>
    val inputLog: SharedFlow<AiStatusMessage>
    val controlLog: SharedFlow<AiStatusMessage>
    val completed: SharedFlow<Unit>

    fun injectUserPrompt(text: String)
    fun start()
    fun stop()

    class Factory {
         fun create(config: SynthControlAgentConfig): SynthControlAgent
    }
}

sealed interface SynthAgentState {
    data object Idle : SynthAgentState
    data object Starting : SynthAgentState
    data object Processing : SynthAgentState
    data class Playing(val description: String) : SynthAgentState
    data class Error(val message: String) : SynthAgentState
}

data class AiStatusMessage(
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = 0L
)
