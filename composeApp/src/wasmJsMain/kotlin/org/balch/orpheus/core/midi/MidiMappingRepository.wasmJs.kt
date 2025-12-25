package org.balch.orpheus.core.midi

import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * WASM implementation of MidiMappingRepository using browser localStorage.
 */
actual class MidiMappingRepository actual constructor() {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val keyPrefix = "orpheus_midi_"

    private fun keyForDevice(deviceName: String): String {
        // Sanitize device name for use as localStorage key
        val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$keyPrefix$safeName"
    }

    actual suspend fun save(deviceName: String, mapping: MidiMappingState) {
        try {
            val key = keyForDevice(deviceName)
            val jsonString = json.encodeToString(mapping.forPersistence())
            localStorage[key] = jsonString
            Logger.info { "Saved MIDI mappings for '$deviceName' to localStorage" }
        } catch (e: Exception) {
            Logger.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return try {
            val key = keyForDevice(deviceName)
            val jsonString = localStorage[key]
            if (jsonString != null) {
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                Logger.info { "Loaded MIDI mappings for '$deviceName' from localStorage" }
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
            val key = keyForDevice(deviceName)
            localStorage.removeItem(key)
            Logger.info { "Deleted MIDI mappings for '$deviceName'" }
        } catch (e: Exception) {
            Logger.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return try {
            val devices = mutableListOf<String>()
            for (i in 0 until localStorage.length) {
                val key = localStorage.key(i)
                if (key != null && key.startsWith(keyPrefix)) {
                    devices.add(key.removePrefix(keyPrefix))
                }
            }
            devices
        } catch (e: Exception) {
            Logger.error { "Failed to list MIDI mapping devices: ${e.message}" }
            emptyList()
        }
    }
}
