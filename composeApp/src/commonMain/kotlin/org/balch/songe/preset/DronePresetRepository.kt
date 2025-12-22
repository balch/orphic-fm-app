package org.balch.songe.preset

/**
 * Platform-specific repository for persisting Drone presets.
 */
expect class DronePresetRepository() {
    /**
     * Save a preset. If a preset with the same name exists, it will be overwritten.
     */
    suspend fun save(preset: DronePreset)
    
    /**
     * Load a preset by name.
     * @return The preset, or null if not found
     */
    suspend fun load(name: String): DronePreset?
    
    /**
     * Delete a preset by name.
     */
    suspend fun delete(name: String)
    
    /**
     * List all saved presets.
     * @return List of presets sorted by creation date (newest first)
     */
    suspend fun list(): List<DronePreset>
}
