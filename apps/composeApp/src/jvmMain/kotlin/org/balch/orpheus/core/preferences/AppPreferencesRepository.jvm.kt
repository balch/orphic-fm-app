package org.balch.orpheus.core.preferences

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import java.io.File

/**
 * JVM implementation of AppPreferencesRepository using JSON files.
 * Stores settings in ~/.config/orpheus/settings.json
 */
@Inject
class JvmAppPreferencesRepository : AppPreferencesRepository {
    private val log = logging("JvmAppPreferencesRepository")

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
        return runCatchingSuspend {
            if (settingsFile.exists()) {
                val jsonString = settingsFile.readText()
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
            settingsFile.writeText(jsonString)
        }.onFailure { e ->
            log.error { "Failed to save preferences: ${e.message}" }
        }
    }
}
