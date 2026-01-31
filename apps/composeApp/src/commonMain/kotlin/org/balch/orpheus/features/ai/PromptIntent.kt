package org.balch.orpheus.features.ai

/**
 * Intent for sending a prompt to the agent.
 */
data class PromptIntent(
    val prompt: String,
    val displayText: String = prompt,
)
