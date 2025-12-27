package org.balch.orpheus.core.presets

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import java.io.File

/**
 * Android implementation of DronePresetRepository using JSON files.
 * Stores presets in the app's files directory.
 * Context is injected via DI.
 */
@Inject
class AndroidDronePresetRepository(
    private val context: Context
) : DronePresetRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val presetsDir: File by lazy {
        File(context.filesDir, "presets").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Logger.info { "Created presets directory: ${dir.absolutePath}" }
            }
        }
    }

    private fun fileForPreset(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(presetsDir, "$safeName.json")
    }

    override suspend fun save(preset: DronePreset) {
        try {
            val file = fileForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            file.writeText(jsonString)
            Logger.info { "Saved preset '${preset.name}'" }
        } catch (e: Exception) {
            Logger.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): DronePreset? {
        return try {
            val file = fileForPreset(name)
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString<DronePreset>(jsonString)
            } else null
        } catch (e: Exception) {
            Logger.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }

    override suspend fun delete(name: String) {
        try {
            val file = fileForPreset(name)
            if (file.exists()) {
                file.delete()
                Logger.info { "Deleted preset '$name'" }
            }
        } catch (e: Exception) {
            Logger.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    override suspend fun list(): List<DronePreset> {
        return try {
            presetsDir.listFiles()
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
