package org.balch.songe.core.midi

import kotlinx.browser.localStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.songe.util.Logger

/**
 * WASM/JS implementation of MidiMappingRepository using browser localStorage.
 */
actual class MidiMappingRepository actual constructor() {
    
    private val json = Json { 
        ignoreUnknownKeys = true
    }
    
    private val storageKeyPrefix = "songe_midi_mapping_"
    private val deviceListKey = "songe_midi_devices"
    
    private fun storageKey(deviceName: String): String {
        val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$storageKeyPrefix$safeName"
    }
    
    actual suspend fun save(deviceName: String, mapping: MidiMappingState) {
        try {
            val key = storageKey(deviceName)
            val jsonString = json.encodeToString(mapping.forPersistence())
            localStorage.setItem(key, jsonString)
            
            // Update device list
            val devices = loadDeviceList().toMutableSet()
            devices.add(deviceName)
            saveDeviceList(devices.toList())
            
            Logger.info { "Saved MIDI mappings for '$deviceName' to localStorage" }
        } catch (e: Exception) {
            Logger.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }
    
    actual suspend fun load(deviceName: String): MidiMappingState? {
        return try {
            val key = storageKey(deviceName)
            localStorage.getItem(key)?.let { jsonString ->
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                Logger.info { "Loaded MIDI mappings for '$deviceName' from localStorage" }
                mapping
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load MIDI mappings for '$deviceName': ${e.message}" }
            null
        }
    }
    
    actual suspend fun delete(deviceName: String) {
        try {
            val key = storageKey(deviceName)
            localStorage.removeItem(key)
            
            // Update device list
            val devices = loadDeviceList().toMutableList()
            devices.remove(deviceName)
            saveDeviceList(devices)
            
            Logger.info { "Deleted MIDI mappings for '$deviceName' from localStorage" }
        } catch (e: Exception) {
            Logger.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }
    
    actual suspend fun listDevices(): List<String> {
        return loadDeviceList()
    }
    
    private fun loadDeviceList(): List<String> {
        return try {
            localStorage.getItem(deviceListKey)?.let { jsonString ->
                json.decodeFromString<List<String>>(jsonString)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveDeviceList(devices: List<String>) {
        try {
            val jsonString = json.encodeToString(devices)
            localStorage.setItem(deviceListKey, jsonString)
        } catch (e: Exception) {
            Logger.error { "Failed to save device list: ${e.message}" }
        }
    }
}
