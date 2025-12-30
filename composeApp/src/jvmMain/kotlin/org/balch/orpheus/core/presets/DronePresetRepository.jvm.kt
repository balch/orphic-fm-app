package org.balch.orpheus.core.presets

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JVM implementation of DronePresetRepository using JSON files.
 * Stores presets in ~/.config/orpheus/presets/
 */
@Inject
class JvmDronePresetRepository : DronePresetRepository {
    private val log = logging("JvmDronePresetRepository")


    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val presetsDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".config/orpheus/presets").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                log.info { "Created presets directory: ${dir.absolutePath}" }
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
            log.info { "Saved preset '${preset.name}' to ${file.name}" }
        } catch (e: Exception) {
            log.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): DronePreset? {
        return try {
            val file = fileForPreset(name)
            if (file.exists()) {
                val jsonString = file.readText()
                val preset = json.decodeFromString<DronePreset>(jsonString)
                log.info { "Loaded preset '${name}' from ${file.name}" }
                preset
            } else {
                log.debug { "No preset found: '$name'" }
                null
            }
        } catch (e: Exception) {
            log.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }

    override suspend fun delete(name: String) {
        try {
            val file = fileForPreset(name)
            if (file.exists()) {
                file.delete()
                log.info { "Deleted preset '$name'" }
            }
        } catch (e: Exception) {
            log.error { "Failed to delete preset '$name': ${e.message}" }
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
                        log.warn { "Failed to parse preset file ${file.name}: ${e.message}" }
                        null
                    }
                }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        } catch (e: Exception) {
            log.error { "Failed to list presets: ${e.message}" }
            emptyList()
        }
    }
}
