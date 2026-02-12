package org.balch.orpheus.core.preferences

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * WASM implementation of AppPreferencesRepository using browser localStorage.
 */
@Inject
class WasmAppPreferencesRepository : AppPreferencesRepository {
    private val log = logging("WasmAppPreferencesRepository")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsKey = "orpheus_app_preferences"

    override suspend fun load(): AppPreferences {
        return runCatchingSuspend {
            val jsonString = localStorage[settingsKey]
            if (jsonString != null) {
                json.decodeFromString<AppPreferences>(jsonString)
            } else {
                AppPreferences()
            }
        }.onFailure { e ->
            log.error { "Failed to load preferences: ${e.message}" }
        }.getOrDefault(AppPreferences())
    }

    override suspend fun save(preferences: AppPreferences) {
        runCatchingSuspend {
            val jsonString = json.encodeToString(preferences)
            localStorage[settingsKey] = jsonString
        }.onFailure { e ->
            log.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
