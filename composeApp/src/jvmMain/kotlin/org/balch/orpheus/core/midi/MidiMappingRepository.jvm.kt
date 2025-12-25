package org.balch.orpheus.core.midi

import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import java.io.File

/**
 * JVM implementation of MidiMappingRepository using JSON files.
 * Stores mappings in ~/.config/orpheus/midi-mappings/
 */
actual class MidiMappingRepository actual constructor() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val configDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".config/orpheus/midi-mappings").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Logger.info { "Created MIDI mappings directory: ${dir.absolutePath}" }
            }
        }
    }

    private fun fileForDevice(deviceName: String): File {
        // Sanitize device name for use as filename
        val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(configDir, "$safeName.json")
    }

    actual suspend fun save(deviceName: String, mapping: MidiMappingState) {
        try {
            val file = fileForDevice(deviceName)
            val jsonString = json.encodeToString(mapping.forPersistence())
            file.writeText(jsonString)
            Logger.info { "Saved MIDI mappings for '$deviceName' to ${file.name}" }
        } catch (e: Exception) {
            Logger.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return try {
            val file = fileForDevice(deviceName)
            if (file.exists()) {
                val jsonString = file.readText()
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                Logger.info { "Loaded MIDI mappings for '$deviceName' from ${file.name}" }
                mapping
            } else {
                Logger.debug { "No saved MIDI mappings for '$deviceName'" }
                null
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load MIDI mappings for '$deviceName': ${e.message}" }
            null
        }
    }

    actual suspend fun delete(deviceName: String) {
        try {
            val file = fileForDevice(deviceName)
            if (file.exists()) {
                file.delete()
                Logger.info { "Deleted MIDI mappings for '$deviceName'" }
            }
        } catch (e: Exception) {
            Logger.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return try {
            configDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        } catch (e: Exception) {
            Logger.error { "Failed to list MIDI mapping devices: ${e.message}" }
            emptyList()
        }
    }
}
