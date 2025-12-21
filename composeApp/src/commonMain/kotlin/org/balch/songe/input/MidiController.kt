package org.balch.songe.input

/**
 * Callback interface for MIDI events.
 */
interface MidiEventListener {
    /**
     * Called when a MIDI note on is received.
     * @param note MIDI note number (0-127)
     * @param velocity Velocity (1-127)
     */
    fun onNoteOn(note: Int, velocity: Int)
    
    /**
     * Called when a MIDI note off is received.
     * @param note MIDI note number (0-127)
     */
    fun onNoteOff(note: Int)
    
    /**
     * Called when a MIDI control change is received.
     * @param controller CC number (0-127)
     * @param value Value (0-127)
     */
    fun onControlChange(controller: Int, value: Int) {}
    
    /**
     * Called when pitch bend is received.
     * @param value Pitch bend value (-8192 to 8191, 0 = center)
     */
    fun onPitchBend(value: Int) {}
}

/**
 * Platform-agnostic MIDI controller interface.
 */
expect class MidiController() {
    /**
     * Get list of available MIDI input device names.
     */
    fun getAvailableDevices(): List<String>
    
    /**
     * Open a MIDI device by name.
     * @param deviceName Name of the device to open
     * @return true if successfully opened
     */
    fun openDevice(deviceName: String): Boolean
    
    /**
     * Close the currently open device.
     */
    fun closeDevice()
    
    /**
     * Start listening for MIDI events.
     */
    fun start(listener: MidiEventListener)
    
    /**
     * Stop listening for MIDI events.
     */
    fun stop()
    
    /**
     * Whether a device is currently open.
     */
    val isOpen: Boolean
    
    /**
     * Name of the currently open device, or null.
     */
    val currentDeviceName: String?
}
