package org.balch.orpheus.core.midi

import com.diamondedge.logging.logging
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * WASM implementation of MidiMappingRepository using browser localStorage.
 */
actual class MidiMappingRepository actual constructor() {
    private val log = logging("MidiMappingRepository")

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
        runCatchingSuspend {
            val key = keyForDevice(deviceName)
            val jsonString = json.encodeToString(mapping.forPersistence())
            localStorage[key] = jsonString
            log.info { "Saved MIDI mappings for '$deviceName' to localStorage" }
        }.onFailure { e ->
            log.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return runCatchingSuspend {
            val key = keyForDevice(deviceName)
            val jsonString = localStorage[key]
            if (jsonString != null) {
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                log.d { "Loaded MIDI mappings for '$deviceName' from localStorage" }
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
            localStorage.removeItem(key)
            log.info { "Deleted MIDI mappings for '$deviceName'" }
        }.onFailure { e ->
            log.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return runCatchingSuspend {
            val devices = mutableListOf<String>()
            for (i in 0 until localStorage.length) {
                val key = localStorage.key(i)
                if (key != null && key.startsWith(keyPrefix)) {
                    devices.add(key.removePrefix(keyPrefix))
                }
            }
            devices
        }.onFailure { e ->
            log.error { "Failed to list MIDI mapping devices: ${e.message}" }
        }.getOrDefault(emptyList())
    }
}
