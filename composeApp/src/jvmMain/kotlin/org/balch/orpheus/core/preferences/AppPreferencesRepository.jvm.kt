package org.balch.orpheus.core.preferences

import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import java.io.File

/**
 * JVM implementation of AppPreferencesRepository using JSON files.
 * Stores settings in ~/.config/orpheus/settings.json
 */
@Inject
class JvmAppPreferencesRepository : AppPreferencesRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsFile: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".config/orpheus/settings.json").also { file ->
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
        }
    }

    override suspend fun load(): AppPreferences {
        return try {
            if (settingsFile.exists()) {
                val jsonString = settingsFile.readText()
                json.decodeFromString<AppPreferences>(jsonString)
            } else {
                AppPreferences()
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load preferences: ${e.message}" }
            AppPreferences()
        }
    }

    override suspend fun save(preferences: AppPreferences) {
        try {
            val jsonString = json.encodeToString(preferences)
            settingsFile.writeText(jsonString)
        } catch (e: Exception) {
            Logger.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
