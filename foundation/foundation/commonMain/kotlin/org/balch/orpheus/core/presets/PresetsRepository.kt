package org.balch.orpheus.core.presets

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-level repository that provides efficient access to all presets (factory + user).
 * 
 * This is a singleton that caches presets for efficient access throughout the app.
 * Use this instead of accessing DronePresetRepository directly when you need
 * quick access to the full preset list or specific presets like "Default".
 */
@SingleIn(AppScope::class)
@Inject
class PresetsRepository(
    private val dronePresetRepository: DronePresetRepository,
    factoryPatches: Set<SynthPatch>
) {
    // Factory presets sorted by name
    private val factoryPresets: List<DronePreset> by lazy {
        factoryPatches
            .map { it.preset }
            .sortedBy { it.name }
    }
    
    private val _factoryPresetNames: Set<String> by lazy {
        factoryPatches.map { it.name }.toSet()
    }
    
    // Cached combined preset list
    private val _allPresets = MutableStateFlow(factoryPresets)
    val allPresets: StateFlow<List<DronePreset>> = _allPresets.asStateFlow()
    
    private val mutex = Mutex()
    private var isLoaded = false
    
    /**
     * Get all presets (factory + user). Loads from storage if not yet loaded.
     * Results are cached for efficient subsequent access.
     */
    suspend fun getAll(): List<DronePreset> {
        ensureLoaded()
        return _allPresets.value
    }
    
    /**
     * Get a preset by name. Returns null if not found.
     */
    suspend fun getByName(name: String): DronePreset? {
        ensureLoaded()
        return _allPresets.value.find { it.name == name }
    }
    
    /**
     * Get the Default preset. Never returns null since Default is a factory preset.
     */
    suspend fun getDefault(): DronePreset {
        ensureLoaded()
        return _allPresets.value.find { it.name == "Default" } 
            ?: factoryPresets.first() // Fallback to first factory preset
    }
    
    /**
     * Check if a preset is a factory preset (read-only).
     */
    fun isFactoryPreset(name: String): Boolean = name in _factoryPresetNames
    
    /**
     * Get the set of factory preset names.
     */
    fun getFactoryPresetNames(): Set<String> = _factoryPresetNames
    
    /**
     * Save a preset (user presets only). Refreshes the cache.
     */
    suspend fun save(preset: DronePreset) {
        dronePresetRepository.save(preset)
        refreshCache()
    }
    
    /**
     * Delete a preset (user presets only). Refreshes the cache.
     */
    suspend fun delete(name: String) {
        dronePresetRepository.delete(name)
        refreshCache()
    }
    
    /**
     * Force reload of presets from storage.
     */
    suspend fun refreshCache() {
        mutex.withLock {
            val userPresets = dronePresetRepository.list()
            _allPresets.value = factoryPresets + userPresets
            isLoaded = true
        }
    }
    
    private suspend fun ensureLoaded() {
        if (!isLoaded) {
            refreshCache()
        }
    }
}
