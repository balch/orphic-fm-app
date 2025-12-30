package org.balch.orpheus.core.ai

import kotlinx.serialization.Serializable

/**
 * Supported AI providers.
 */
enum class AiProvider(val id: String, val displayName: String) {
    GOOGLE("google", "Google Gemini"),
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic Claude");
    
    companion object {
        fun fromId(id: String) = entries.find { it.id == id } ?: GOOGLE
    }
}

/**
 * Configuration for a user-provided API key.
 */
@Serializable
data class UserApiKeyConfig(
    val provider: String,
    val apiKey: String,
)

/**
 * Validates an API key format for a given provider.
 */
fun AiProvider.validateKeyFormat(key: String): Boolean {
    if (key.isBlank()) return false
    return when (this) {
        AiProvider.GOOGLE -> key.length >= 30 // Gemini keys are ~39 chars
        AiProvider.OPENAI -> key.startsWith("sk-") && key.length >= 40
        AiProvider.ANTHROPIC -> key.startsWith("sk-ant-") && key.length >= 40
    }
}
