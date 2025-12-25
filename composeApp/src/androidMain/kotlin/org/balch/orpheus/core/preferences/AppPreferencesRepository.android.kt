package org.balch.orpheus.core.preferences

import android.content.Context
import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import java.io.File

actual class AppPreferencesRepository actual constructor() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        var appContext: Context? = null
    }

    private val settingsFile: File? by lazy {
        appContext?.filesDir?.let { filesDir ->
            File(filesDir, "settings.json")
        }
    }

    actual suspend fun load(): AppPreferences {
        return try {
            settingsFile?.let { file ->
                if (file.exists()) {
                    val jsonString = file.readText()
                    json.decodeFromString<AppPreferences>(jsonString)
                } else {
                    AppPreferences()
                }
            } ?: AppPreferences()
        } catch (e: Exception) {
            Logger.error { "Failed to load preferences: ${e.message}" }
            AppPreferences()
        }
    }

    actual suspend fun save(preferences: AppPreferences) {
        try {
            settingsFile?.let { file ->
                val jsonString = json.encodeToString(preferences)
                file.writeText(jsonString)
            }
        } catch (e: Exception) {
            Logger.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
