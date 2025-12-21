package org.balch.songe.input

import kotlinx.browser.window
import org.balch.songe.util.Logger
import org.w3c.dom.events.Event

/**
 * WASM actual implementation of MidiController using Web MIDI API.
 */
actual class MidiController actual constructor() {
    
    private var midiAccess: dynamic = null
    private var listener: MidiEventListener? = null
    private var _currentDeviceName: String? = null
    
    actual fun getAvailableDevices(): List<String> {
        val access = midiAccess ?: return emptyList()
        val devices = mutableListOf<String>()
        
        try {
            val inputs = access.inputs
            inputs.forEach { entry: dynamic ->
                val input = entry[1]
                devices.add(input.name as String)
            }
        } catch (e: Exception) {
            Logger.error { "Error enumerating MIDI devices: ${e.message}" }
        }
        
        return devices
    }
    
    actual fun openDevice(deviceName: String): Boolean {
        val access = midiAccess
        if (access == null) {
            Logger.warn { "MIDI access not available" }
            return false
        }
        
        try {
            val inputs = access.inputs
            var found = false
            inputs.forEach { entry: dynamic ->
                val input = entry[1]
                if (input.name == deviceName) {
                    input.onmidimessage = { event: dynamic ->
                        handleMidiMessage(event)
                    }
                    _currentDeviceName = deviceName
                    found = true
                    Logger.info { "Opened MIDI device: $deviceName" }
                }
            }
            return found
        } catch (e: Exception) {
            Logger.error { "Failed to open MIDI device: ${e.message}" }
            return false
        }
    }
    
    actual fun closeDevice() {
        val access = midiAccess ?: return
        
        try {
            val inputs = access.inputs
            inputs.forEach { entry: dynamic ->
                val input = entry[1]
                input.onmidimessage = null
            }
        } catch (e: Exception) {
            Logger.warn { "Error closing MIDI device: ${e.message}" }
        }
        
        _currentDeviceName = null
    }
    
    actual fun start(listener: MidiEventListener) {
        this.listener = listener
        
        if (midiAccess == null) {
            requestMidiAccess()
        }
    }
    
    actual fun stop() {
        closeDevice()
        listener = null
    }
    
    actual val isOpen: Boolean
        get() = _currentDeviceName != null
    
    actual val currentDeviceName: String?
        get() = _currentDeviceName
    
    private fun requestMidiAccess() {
        try {
            val navigator = window.navigator.asDynamic()
            if (navigator.requestMIDIAccess == undefined) {
                Logger.warn { "Web MIDI API not supported in this browser" }
                return
            }
            
            navigator.requestMIDIAccess().then { access: dynamic ->
                midiAccess = access
                Logger.info { "Web MIDI API access granted" }
                
                // Auto-connect to first available device
                val devices = getAvailableDevices()
                if (devices.isNotEmpty()) {
                    openDevice(devices.first())
                }
                
                // Listen for device changes
                access.onstatechange = { _: Event ->
                    Logger.debug { "MIDI device state changed" }
                }
            }.catch { error: dynamic ->
                Logger.error { "Failed to get MIDI access: $error" }
            }
        } catch (e: Exception) {
            Logger.error { "Error requesting MIDI access: ${e.message}" }
        }
    }
    
    private fun handleMidiMessage(event: dynamic) {
        val currentListener = listener ?: return
        
        val data = event.data
        if (data == null || data.length < 2) return
        
        val status = (data[0] as Int) and 0xF0
        val note = data[1] as Int
        val velocity = if (data.length > 2) data[2] as Int else 0
        
        when (status) {
            0x90 -> { // Note On
                if (velocity > 0) {
                    currentListener.onNoteOn(note, velocity)
                } else {
                    currentListener.onNoteOff(note)
                }
            }
            0x80 -> { // Note Off
                currentListener.onNoteOff(note)
            }
            0xB0 -> { // Control Change
                currentListener.onControlChange(note, velocity)
            }
            0xE0 -> { // Pitch Bend
                val value = ((velocity shl 7) or note) - 8192
                currentListener.onPitchBend(value)
            }
        }
    }
}
