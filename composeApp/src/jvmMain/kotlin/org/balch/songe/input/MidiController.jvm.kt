package org.balch.songe.input

import org.balch.songe.util.Logger
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/**
 * JVM actual implementation of MidiController using javax.sound.midi.
 */
actual class MidiController actual constructor() {
    
    private var currentDevice: MidiDevice? = null
    private var listener: MidiEventListener? = null
    
    actual fun getAvailableDevices(): List<String> {
        return MidiSystem.getMidiDeviceInfo()
            .mapNotNull { info ->
                try {
                    val device = MidiSystem.getMidiDevice(info)
                    // Only include devices that can transmit (have transmitters)
                    if (device.maxTransmitters != 0) {
                        info.name
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .distinct()
    }
    
    actual fun openDevice(deviceName: String): Boolean {
        closeDevice()
        
        val info = MidiSystem.getMidiDeviceInfo().find { it.name == deviceName }
        if (info == null) {
            Logger.warn { "MIDI device not found: $deviceName" }
            return false
        }
        
        return try {
            val device = MidiSystem.getMidiDevice(info)
            if (device.maxTransmitters == 0) {
                Logger.warn { "MIDI device cannot transmit: $deviceName" }
                return false
            }
            
            device.open()
            currentDevice = device
            Logger.info { "Opened MIDI device: $deviceName" }
            true
        } catch (e: Exception) {
            Logger.error { "Failed to open MIDI device: ${e.message}" }
            false
        }
    }
    
    actual fun closeDevice() {
        try {
            currentDevice?.close()
        } catch (e: Exception) {
            Logger.warn { "Error closing MIDI device: ${e.message}" }
        }
        currentDevice = null
    }
    
    actual fun start(listener: MidiEventListener) {
        this.listener = listener
        
        val device = currentDevice
        if (device == null) {
            Logger.warn { "No MIDI device open, cannot start" }
            return
        }
        
        try {
            val transmitter = device.transmitter
            transmitter.receiver = object : Receiver {
                override fun send(message: MidiMessage, timeStamp: Long) {
                    handleMidiMessage(message)
                }
                
                override fun close() {
                    // Nothing to clean up
                }
            }
            Logger.info { "Started listening for MIDI events" }
        } catch (e: Exception) {
            Logger.error { "Failed to start MIDI listener: ${e.message}" }
        }
    }
    
    actual fun stop() {
        listener = null
        // Transmitter is automatically released when device is closed
    }
    
    actual val isOpen: Boolean
        get() = currentDevice?.isOpen == true
    
    actual val currentDeviceName: String?
        get() = currentDevice?.deviceInfo?.name
    
    private fun handleMidiMessage(message: MidiMessage) {
        if (message !is ShortMessage) return
        
        val currentListener = listener ?: return
        
        when (message.command) {
            ShortMessage.NOTE_ON -> {
                val note = message.data1
                val velocity = message.data2
                if (velocity > 0) {
                    currentListener.onNoteOn(note, velocity)
                } else {
                    // Note on with velocity 0 is effectively note off
                    currentListener.onNoteOff(note)
                }
            }
            ShortMessage.NOTE_OFF -> {
                val note = message.data1
                currentListener.onNoteOff(note)
            }
            ShortMessage.CONTROL_CHANGE -> {
                val controller = message.data1
                val value = message.data2
                currentListener.onControlChange(controller, value)
            }
            ShortMessage.PITCH_BEND -> {
                // Pitch bend is 14-bit value: data1 = LSB, data2 = MSB
                val value = (message.data2 shl 7) or message.data1 - 8192
                currentListener.onPitchBend(value)
            }
        }
    }
}
