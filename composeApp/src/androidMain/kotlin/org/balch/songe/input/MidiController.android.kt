package org.balch.songe.input

import org.balch.songe.util.Logger

/**
 * Android actual implementation of MidiController.
 * Currently a stub - Android uses touch input primarily.
 * Full MIDI implementation can be added later using Android's MIDI API.
 */
actual class MidiController actual constructor() {
    
    actual fun getAvailableDevices(): List<String> {
        // Android MIDI requires API 23+ and MidiManager
        Logger.debug { "Android MIDI not yet implemented" }
        return emptyList()
    }
    
    actual fun openDevice(deviceName: String): Boolean {
        Logger.debug { "Android MIDI not yet implemented" }
        return false
    }
    
    actual fun closeDevice() {
        // No-op for now
    }
    
    actual fun start(listener: MidiEventListener) {
        Logger.debug { "Android MIDI not yet implemented" }
    }
    
    actual fun stop() {
        // No-op for now
    }
    
    actual val isOpen: Boolean
        get() = false
    
    actual val currentDeviceName: String?
        get() = null
}
