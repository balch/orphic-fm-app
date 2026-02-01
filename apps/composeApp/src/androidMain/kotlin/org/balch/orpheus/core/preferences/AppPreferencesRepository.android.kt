package org.balch.orpheus.core.preferences

import android.content.Context
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Android implementation of AppPreferencesRepository.
 * Stores preferences as JSON in the app's files directory.
 * Context is injected via DI.
 */
@Inject
class AndroidAppPreferencesRepository(
    private val context: Context
) : AppPreferencesRepository {

    private val log = logging()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsFile: File by lazy {
        File(context.filesDir, "settings.json")
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
            log.error { "Failed to load preferences: ${e.message}" }
            AppPreferences()
        }
    }

    override suspend fun save(preferences: AppPreferences) {
        try {
            val jsonString = json.encodeToString(preferences)
            settingsFile.writeText(jsonString)
        } catch (e: Exception) {
            log.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
