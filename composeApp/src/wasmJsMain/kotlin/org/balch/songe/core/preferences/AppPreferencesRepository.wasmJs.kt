package org.balch.songe.core.preferences

import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.balch.songe.util.Logger
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * WASM implementation of AppPreferencesRepository using browser localStorage.
 */
actual class AppPreferencesRepository actual constructor() {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsKey = "songe_app_preferences"

    actual suspend fun load(): AppPreferences {
        return try {
            val jsonString = localStorage[settingsKey]
            if (jsonString != null) {
                json.decodeFromString<AppPreferences>(jsonString)
            } else {
                AppPreferences()
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load preferences: ${e.message}" }
            AppPreferences()
        }
    }

    actual suspend fun save(preferences: AppPreferences) {
        try {
            val jsonString = json.encodeToString(preferences)
            localStorage[settingsKey] = jsonString
        } catch (e: Exception) {
            Logger.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
