package org.balch.orpheus.core.midi

import com.diamondedge.logging.logging
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import java.io.File

/**
 * JVM implementation of MidiMappingRepository using JSON files.
 * Stores mappings in ~/.config/orpheus/midi-mappings/
 */
actual class MidiMappingRepository actual constructor() {
    private val log = logging("MidiMappingRepository")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val configDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".config/orpheus/midi-mappings").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                log.info { "Created MIDI mappings directory: ${dir.absolutePath}" }
            }
        }
    }

    private fun fileForDevice(deviceName: String): File {
        // Sanitize device name for use as filename
        val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(configDir, "$safeName.json")
    }

    actual suspend fun save(deviceName: String, mapping: MidiMappingState) {
        runCatchingSuspend {
            val file = fileForDevice(deviceName)
            val jsonString = json.encodeToString(mapping.forPersistence())
            file.writeText(jsonString)
            log.info { "Saved MIDI mappings for '$deviceName' to ${file.name}" }
        }.onFailure { e ->
            log.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return runCatchingSuspend {
            val file = fileForDevice(deviceName)
            if (file.exists()) {
                val jsonString = file.readText()
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                log.d { "Loaded MIDI mappings for '$deviceName' from ${file.name}" }
                mapping
            } else {
                log.debug { "No saved MIDI mappings for '$deviceName'" }
                null
            }
        }.onFailure { e ->
            log.error { "Failed to load MIDI mappings for '$deviceName': ${e.message}" }
        }.getOrNull()
    }

    actual suspend fun delete(deviceName: String) {
        runCatchingSuspend {
            val file = fileForDevice(deviceName)
            if (file.exists()) {
                file.delete()
                log.info { "Deleted MIDI mappings for '$deviceName'" }
            }
        }.onFailure { e ->
            log.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return runCatchingSuspend {
            configDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        }.onFailure { e ->
            log.error { "Failed to list MIDI mapping devices: ${e.message}" }
        }.getOrDefault(emptyList())
    }
}
