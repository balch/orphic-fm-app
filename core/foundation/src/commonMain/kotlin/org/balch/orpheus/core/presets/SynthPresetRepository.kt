package org.balch.orpheus.core.presets

/**
 * Repository for persisting Synth presets.
 * Platform-specific implementations are provided via DI.
 */
interface SynthPresetRepository {
    /**
     * Save a preset. If a preset with the same name exists, it will be overwritten.
     */
    suspend fun save(preset: SynthPreset)

    /**
     * Load a preset by name.
     * @return The preset, or null if not found
     */
    suspend fun load(name: String): SynthPreset?

    /**
     * Delete a preset by name.
     */
    suspend fun delete(name: String)

    /**
     * List all saved presets.
     * @return List of presets sorted by creation date (newest first)
     */
    suspend fun list(): List<SynthPreset>
}
