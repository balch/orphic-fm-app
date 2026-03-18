package org.balch.orpheus.core.midi

import com.diamondedge.logging.logging
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of MidiMappingRepository using NSUserDefaults.
 */
actual class MidiMappingRepository actual constructor() {
    private val log = logging("MidiMappingRepository")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val keyPrefix = "orpheus_midi_"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    private fun keyForDevice(deviceName: String): String {
        val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$keyPrefix$safeName"
    }

    actual suspend fun save(deviceName: String, mapping: MidiMappingState) {
        runCatchingSuspend {
            val key = keyForDevice(deviceName)
            val jsonString = json.encodeToString(mapping.forPersistence())
            defaults.setObject(jsonString, key)
            log.info { "Saved MIDI mappings for '$deviceName' to NSUserDefaults" }
        }.onFailure { e ->
            log.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return runCatchingSuspend {
            val key = keyForDevice(deviceName)
            val jsonString = defaults.stringForKey(key)
            if (jsonString != null) {
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                log.debug { "Loaded MIDI mappings for '$deviceName' from NSUserDefaults" }
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
            val key = keyForDevice(deviceName)
            defaults.removeObjectForKey(key)
            log.info { "Deleted MIDI mappings for '$deviceName'" }
        }.onFailure { e ->
            log.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return runCatchingSuspend {
            val allKeys = defaults.dictionaryRepresentation().keys
                .filterIsInstance<String>()
                .filter { it.startsWith(keyPrefix) }
                .map { it.removePrefix(keyPrefix) }
            allKeys
        }.onFailure { e ->
            log.error { "Failed to list MIDI mapping devices: ${e.message}" }
        }.getOrDefault(emptyList())
    }
}
