package org.balch.songe.core.presets

import android.content.Context
import kotlinx.serialization.json.Json
import org.balch.songe.util.Logger
import java.io.File

/**
 * Android implementation of DronePresetRepository using JSON files.
 * Stores presets in the app's files directory.
 */
actual class DronePresetRepository actual constructor() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Will be set by the application on startup
    companion object {
        var appContext: Context? = null
    }

    private val presetsDir: File? by lazy {
        appContext?.filesDir?.let { filesDir ->
            File(filesDir, "presets").also { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                    Logger.info { "Created presets directory: ${dir.absolutePath}" }
                }
            }
        }
    }

    private fun fileForPreset(name: String): File? {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return presetsDir?.let { File(it, "$safeName.json") }
    }

    actual suspend fun save(preset: DronePreset) {
        try {
            val file = fileForPreset(preset.name) ?: return
            val jsonString = json.encodeToString(preset)
            file.writeText(jsonString)
            Logger.info { "Saved preset '${preset.name}'" }
        } catch (e: Exception) {
            Logger.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    actual suspend fun load(name: String): DronePreset? {
        return try {
            val file = fileForPreset(name) ?: return null
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString<DronePreset>(jsonString)
            } else null
        } catch (e: Exception) {
            Logger.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }

    actual suspend fun delete(name: String) {
        try {
            fileForPreset(name)?.let { file ->
                if (file.exists()) {
                    file.delete()
                    Logger.info { "Deleted preset '$name'" }
                }
            }
        } catch (e: Exception) {
            Logger.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    actual suspend fun list(): List<DronePreset> {
        return try {
            presetsDir?.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        json.decodeFromString<DronePreset>(file.readText())
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        } catch (e: Exception) {
            Logger.error { "Failed to list presets: ${e.message}" }
            emptyList()
        }
    }
}
