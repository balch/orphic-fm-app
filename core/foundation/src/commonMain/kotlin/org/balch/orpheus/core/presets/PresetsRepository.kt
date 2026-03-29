package org.balch.orpheus.core.presets

import com.diamondedge.logging.logging
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
 * quick access to the full preset list or specific presets like "Orpheus".
 */
@SingleIn(AppScope::class)
@Inject
class PresetsRepository(
    private val synthPresetRepository: SynthPresetRepository,
    private val factoryPatches: Set<SynthPatch>
) {
    private val log = logging("PresetsRepository")

    init {
        log.info { "PresetsRepository created with ${factoryPatches.size} factory patches: ${factoryPatches.map { it.name }}" }
    }

    private val _factoryPresetNames: Set<String> =
        factoryPatches.map { it.name }.toSet()

    // Loaded lazily on first refreshCache() call
    private var factoryPresets: List<SynthPreset>? = null

    private suspend fun getFactoryPresets(): List<SynthPreset> {
        factoryPresets?.let { return it }
        return factoryPatches
            .map { it.loadPreset() }
            .sortedBy { it.name }
            .also { factoryPresets = it }
    }

    // Cached combined preset list — starts empty, populated on first ensureLoaded()
    private val _allPresets = MutableStateFlow<List<SynthPreset>>(emptyList())
    val allPresets: StateFlow<List<SynthPreset>> = _allPresets.asStateFlow()
    
    private val mutex = Mutex()
    private var isLoaded = false
    
    /**
     * Get all presets (factory + user). Loads from storage if not yet loaded.
     * Results are cached for efficient subsequent access.
     */
    suspend fun getAll(): List<SynthPreset> {
        ensureLoaded()
        return _allPresets.value
    }
    
    /**
     * Get a preset by name. Returns null if not found.
     */
    suspend fun getByName(name: String): SynthPreset? {
        ensureLoaded()
        return _allPresets.value.find { it.name == name }
    }
    
    /**
     * Get the default preset (Orpheus). Never returns null since it's a factory preset.
     */
    suspend fun getDefault(): SynthPreset {
        ensureLoaded()
        return _allPresets.value.find { it.name == "Orpheus" }
            ?: getFactoryPresets().first() // Fallback to first factory preset
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
    suspend fun save(preset: SynthPreset) {
        synthPresetRepository.save(preset)
        refreshCache()
    }
    
    /**
     * Delete a preset (user presets only). Refreshes the cache.
     */
    suspend fun delete(name: String) {
        synthPresetRepository.delete(name)
        refreshCache()
    }
    
    /**
     * Force reload of presets from storage.
     */
    suspend fun refreshCache() {
        mutex.withLock {
            // Filter out user presets that duplicate factory preset names
            val factory = getFactoryPresets()
            val userPresets = synthPresetRepository.list()
                .filter { it.name !in _factoryPresetNames }
            log.info { "refreshCache: ${factory.size} factory + ${userPresets.size} user = ${factory.size + userPresets.size} total" }
            _allPresets.value = factory + userPresets
            isLoaded = true
        }
    }
    
    private suspend fun ensureLoaded() {
        if (!isLoaded) {
            refreshCache()
        }
    }
}
