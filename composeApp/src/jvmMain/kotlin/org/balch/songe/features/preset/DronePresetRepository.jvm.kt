package org.balch.songe.features.preset

import kotlinx.serialization.json.Json
import org.balch.songe.util.Logger
import songe.composeapp.generated.resources.Res
import java.io.File

/**
 * JVM implementation of DronePresetRepository using JSON files.
 * Stores presets in ~/.config/songe/presets/
 */
actual class DronePresetRepository actual constructor() {
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private val presetsDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".config/songe/presets").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Logger.info { "Created presets directory: ${dir.absolutePath}" }
            }
        }
    }

    private suspend fun ensurePresetsInitialized() {
        if (presetsDir.listFiles()?.isEmpty() == true) {
            copyBundledPresets(presetsDir)
        }
    }
    
    private suspend fun copyBundledPresets(dir: File) {
        val bundledPresets = listOf(
            "F__Minor_Drift.json",
            "Warm_Pad.json",
            "Dark_Ambient.json"
        )
        bundledPresets.forEach { filename ->
            try {
                val path = "files/presets/$filename"
                val bytes = Res.readBytes(path)
                val targetFile = File(dir, filename)
                targetFile.writeBytes(bytes)
                Logger.info { "Copied bundled preset: $filename" }
            } catch (e: Exception) {
                Logger.error { "Failed to copy bundled preset $filename: ${e.message}" }
            }
        }
    }
    
    private fun fileForPreset(name: String): File {
        // Sanitize name for use as filename
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(presetsDir, "$safeName.json")
    }
    
    actual suspend fun save(preset: DronePreset) {
        ensurePresetsInitialized()
        try {
            val file = fileForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            file.writeText(jsonString)
            Logger.info { "Saved preset '${preset.name}' to ${file.name}" }
        } catch (e: Exception) {
            Logger.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }
    
    actual suspend fun load(name: String): DronePreset? {
        ensurePresetsInitialized()
        return try {
            val file = fileForPreset(name)
            if (file.exists()) {
                val jsonString = file.readText()
                val preset = json.decodeFromString<DronePreset>(jsonString)
                Logger.info { "Loaded preset '${name}' from ${file.name}" }
                preset
            } else {
                Logger.debug { "No preset found: '$name'" }
                null
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }
    
    actual suspend fun delete(name: String) {
        ensurePresetsInitialized()
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
    
    actual suspend fun list(): List<DronePreset> {
        ensurePresetsInitialized()
        return try {
            presetsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        json.decodeFromString<DronePreset>(file.readText())
                    } catch (e: Exception) {
                        Logger.warn { "Failed to parse preset file ${file.name}: ${e.message}" }
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
