package org.balch.orpheus.core.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.BuildKonfig

/**
 * Provides the Gemini API key for AI chatbot functionality.
 * Uses BuildKonfig for cross-platform BuildConfig generation from local.properties.
 */
@SingleIn(AppScope::class)
class GeminiKeyProvider @Inject constructor() {
    val apiKey: String? = BuildKonfig.GEMINI_API_KEY.takeIf { it.isNotEmpty() }
    val isApiKeySet: Boolean get() = !apiKey.isNullOrEmpty()
}
