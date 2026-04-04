package org.balch.orpheus.core.preferences

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of AppPreferencesRepository using NSUserDefaults.
 */
@Inject
class IosAppPreferencesRepository : BaseAppPreferencesRepository() {
    private val log = logging("IosAppPreferencesRepository")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsKey = "orpheus_app_preferences"

    override suspend fun load(): AppPreferences {
        return try {
            val jsonString = NSUserDefaults.standardUserDefaults.stringForKey(settingsKey)
            if (jsonString != null) {
                json.decodeFromString<AppPreferences>(jsonString)
            } else {
                AppPreferences()
            }
        } catch (e: Exception) {
            log.error { "Failed to load preferences: ${e.message}" }
            AppPreferences()
        }
    }

    override suspend fun save(preferences: AppPreferences) {
        try {
            val jsonString = json.encodeToString(preferences)
            NSUserDefaults.standardUserDefaults.setObject(jsonString, forKey = settingsKey)
        } catch (e: Exception) {
            log.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
