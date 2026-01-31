package org.balch.orpheus.core.ai

import kotlinx.serialization.Serializable
import org.balch.orpheus.BuildKonfig

/**
 * Supported AI providers.
 */
sealed class AiProvider(
    val id: String,
    val displayName: String,
    private val keyCallback: (() -> String?)? = null,
) {
    fun defaultKey():String? {
        return keyCallback?.invoke()
            ?.takeIf { validateKeyFormat(it) }
    }

    data object Google : AiProvider("google", "Google Gemini", { BuildKonfig.GEMINI_API_KEY })
    data object OpenAI : AiProvider("openai", "OpenAI")
    data object Anthropic : AiProvider("anthropic", "Anthropic Claude", { BuildKonfig.ANTHROPIC_API_KEY })
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
        AiProvider.Google -> key.length >= 30 // Gemini keys are ~39 chars
        AiProvider.OpenAI -> key.startsWith("sk-") && key.length >= 40
        AiProvider.Anthropic -> key.startsWith("sk-ant-") && key.length >= 40
    }
}
fun deriveAiProviderFromKey(key: String): AiProvider? {
    if (key.isBlank()) return null
    return when  {
        AiProvider.Anthropic.validateKeyFormat(key) -> AiProvider.Anthropic
        AiProvider.OpenAI.validateKeyFormat(key) -> AiProvider.OpenAI
        AiProvider.Google.validateKeyFormat(key) -> AiProvider.Google
        else -> null
    }
}
