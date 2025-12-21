package org.balch.songe.input

/**
 * Platform-specific repository for persisting MIDI mappings.
 * Mappings are stored per-device so different controllers can have different mappings.
 */
expect class MidiMappingRepository() {
    /**
     * Save MIDI mappings for a device.
     * @param deviceName The MIDI device name (e.g., "CoreMIDI4J - S-1")
     * @param mapping The mappings to save (learnTarget is ignored)
     */
    suspend fun save(deviceName: String, mapping: MidiMappingState)
    
    /**
     * Load MIDI mappings for a device.
     * @param deviceName The MIDI device name
     * @return The saved mappings, or null if none exist
     */
    suspend fun load(deviceName: String): MidiMappingState?
    
    /**
     * Delete saved mappings for a device.
     * @param deviceName The MIDI device name
     */
    suspend fun delete(deviceName: String)
    
    /**
     * List all devices that have saved mappings.
     * @return List of device names
     */
    suspend fun listDevices(): List<String>
}
