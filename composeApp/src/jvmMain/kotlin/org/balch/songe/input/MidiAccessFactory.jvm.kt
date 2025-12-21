package org.balch.songe.input

import dev.atsushieno.ktmidi.EmptyMidiAccess
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiInput
import dev.atsushieno.ktmidi.MidiOutput
import dev.atsushieno.ktmidi.MidiPortConnectionState
import dev.atsushieno.ktmidi.MidiPortDetails
import dev.atsushieno.ktmidi.MidiTransportProtocol
import dev.atsushieno.ktmidi.OnMidiReceivedEventListener
import org.balch.songe.util.Logger
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter

/**
 * Custom MidiAccess implementation that uses CoreMIDI4J for device enumeration
 * to properly detect USB MIDI devices on macOS.
 */
class CoreMidiJvmAccess : MidiAccess() {
    
    // Internal Java MIDI device names to filter out
    private val internalDevices = setOf(
        "Real Time Sequencer",
        "Gervill",
        "MIDI OUT"
    )
    
    override val name: String = "CoreMIDI4J-JVM"
    
    override val inputs: Iterable<MidiPortDetails>
        get() {
            val devices = mutableListOf<MidiPortDetails>()
            
            for (info in MidiSystem.getMidiDeviceInfo()) {
                try {
                    val device = MidiSystem.getMidiDevice(info)
                    // Only include devices that can transmit (have transmitters)
                    // and are not internal Java devices
                    if (device.maxTransmitters != 0 && info.name !in internalDevices) {
                        devices.add(CoreMidiPortDetails(info.name, info.name, info.vendor, info.version))
                    }
                } catch (e: Exception) {
                    // Skip devices that can't be opened
                }
            }
            
            return devices.distinctBy { it.id }
        }
    
    override val outputs: Iterable<MidiPortDetails>
        get() = emptyList() // We only care about inputs for now
    
    override suspend fun openInput(portId: String): MidiInput {
        val info = MidiSystem.getMidiDeviceInfo().find { it.name == portId }
            ?: throw IllegalArgumentException("Port ID $portId does not exist.")
        
        val device = MidiSystem.getMidiDevice(info)
        return CoreMidiInputPort(device, portId)
    }
    
    override suspend fun openOutput(portId: String): MidiOutput {
        throw UnsupportedOperationException("Output not implemented")
    }
}

private data class CoreMidiPortDetails(
    override val id: String,
    override val name: String?,
    override val manufacturer: String?,
    override val version: String?
) : MidiPortDetails {
    override val midiTransportProtocol: Int = MidiTransportProtocol.MIDI1
}

private class CoreMidiInputPort(
    private val device: MidiDevice,
    private val portName: String
) : MidiInput {
    
    override val details: MidiPortDetails = CoreMidiPortDetails(
        portName, portName, device.deviceInfo?.vendor, device.deviceInfo?.version
    )
    
    private var _connectionState = MidiPortConnectionState.CLOSED
    override val connectionState: MidiPortConnectionState
        get() = _connectionState
    
    private var transmitter: Transmitter? = null
    
    init {
        try {
            if (!device.isOpen) {
                device.open()
            }
            transmitter = device.transmitter
            _connectionState = MidiPortConnectionState.OPEN
        } catch (e: Exception) {
            Logger.error { "Failed to open MIDI device: ${e.message}" }
            _connectionState = MidiPortConnectionState.CLOSED
        }
    }
    
    override fun setMessageReceivedListener(listener: OnMidiReceivedEventListener) {
        transmitter?.receiver = object : Receiver {
            override fun send(message: MidiMessage?, timeStamp: Long) {
                if (message != null) {
                    listener.onEventReceived(
                        message.message,
                        0,
                        message.length,
                        timeStamp * 1000 // Convert microseconds to nanoseconds
                    )
                }
            }
            
            override fun close() {
                // Nothing to clean up
            }
        }
    }
    
    override fun close() {
        try {
            transmitter?.close()
            device.close()
        } catch (e: Exception) {
            Logger.warn { "Error closing MIDI device: ${e.message}" }
        }
        _connectionState = MidiPortConnectionState.CLOSED
    }
}

/**
 * JVM actual implementation - uses CoreMidiJvmAccess for proper macOS USB MIDI support.
 * CoreMIDI4J is loaded to enable USB MIDI device detection on macOS.
 */
actual fun createMidiAccess(): MidiAccess {
    // CoreMIDI4J enhances javax.sound.midi to see CoreMIDI devices on macOS
    try {
        if (CoreMidiDeviceProvider.isLibraryLoaded()) {
            return CoreMidiJvmAccess()
        }
    } catch (e: Exception) {
        Logger.debug { "CoreMIDI4J not available: ${e.message}" }
    }
    
    // Fallback to empty access if CoreMIDI4J not available
    Logger.warn { "No MIDI backend available" }
    return EmptyMidiAccess()
}
