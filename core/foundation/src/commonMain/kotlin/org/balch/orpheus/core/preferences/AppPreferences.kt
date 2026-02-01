package org.balch.orpheus.core.preferences

import kotlinx.serialization.Serializable

@Serializable
data class AppPreferences(
    val lastVizId: String? = null,
    val lastPresetName: String? = null,
    /** User-provided API keys. Maps provider ID (e.g., "google") to API key. */
    val userApiKeys: Map<String, String> = emptyMap(),
    /** Selected AI model ID (e.g., "flash_25", "pro_25"). */
    val selectedAiModel: String? = null,
)


