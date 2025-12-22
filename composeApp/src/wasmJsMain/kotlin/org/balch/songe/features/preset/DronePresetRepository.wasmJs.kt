package org.balch.songe.features.preset

import kotlinx.browser.localStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.songe.util.Logger

/**
 * WASM/JS implementation of DronePresetRepository using localStorage.
 */
actual class DronePresetRepository actual constructor() {
    
    private val json = Json { 
        ignoreUnknownKeys = true
    }
    
    private val storagePrefix = "songe_preset_"
    private val indexKey = "songe_presets_index"
    
    private fun keyForPreset(name: String): String {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$storagePrefix$safeName"
    }
    
    private fun getPresetIndex(): MutableSet<String> {
        return try {
            localStorage.getItem(indexKey)?.let { indexJson ->
                json.decodeFromString<Set<String>>(indexJson).toMutableSet()
            } ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }
    
    private fun savePresetIndex(index: Set<String>) {
        try {
            localStorage.setItem(indexKey, json.encodeToString(index))
        } catch (e: Exception) {
            Logger.error { "Failed to save preset index: ${e.message}" }
        }
    }
    
    actual suspend fun save(preset: DronePreset) {
        try {
            val key = keyForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            localStorage.setItem(key, jsonString)
            
            // Update index
            val index = getPresetIndex()
            index.add(preset.name)
            savePresetIndex(index)
            
            Logger.info { "Saved preset '${preset.name}'" }
        } catch (e: Exception) {
            Logger.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }
    
    actual suspend fun load(name: String): DronePreset? {
        return try {
            val key = keyForPreset(name)
            localStorage.getItem(key)?.let { jsonString ->
                json.decodeFromString<DronePreset>(jsonString)
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }
    
    actual suspend fun delete(name: String) {
        try {
            val key = keyForPreset(name)
            localStorage.removeItem(key)
            
            // Update index
            val index = getPresetIndex()
            index.remove(name)
            savePresetIndex(index)
            
            Logger.info { "Deleted preset '$name'" }
        } catch (e: Exception) {
            Logger.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }
    
    actual suspend fun list(): List<DronePreset> {
        return try {
            getPresetIndex().mapNotNull { name ->
                load(name)
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Logger.error { "Failed to list presets: ${e.message}" }
            emptyList()
        }
    }
}
