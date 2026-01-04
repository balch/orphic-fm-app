package org.balch.orpheus.core.midi

import com.diamondedge.logging.logging
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiInput
import dev.atsushieno.ktmidi.MidiPortConnectionState
import dev.atsushieno.ktmidi.OnMidiReceivedEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * MIDI Controller using ktmidi library for cross-platform MIDI support.
 *
 * @param midiAccessFactory Factory function to create fresh MidiAccess instances for device re-enumeration
 */
class MidiController(
    private val midiAccessFactory: () -> MidiAccess
) {
    private val log = logging("MidiController")
    private var midiAccess: MidiAccess = midiAccessFactory()
    private var currentInput: MidiInput? = null
    private var listener: MidiEventListener? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Internal Java MIDI device names to filter out
    private val internalDevices = setOf(
        "Real Time Sequencer",
        "Gervill",
        "MIDI OUT"
    )

    /**
     * Get list of available MIDI input device names.
     * This refreshes the device list to detect newly connected devices.
     */
    fun getAvailableDevices(): List<String> {
        // Re-create MidiAccess to get fresh device list (for hot-plug detection)
        midiAccess = midiAccessFactory()

        return midiAccess.inputs
            .mapNotNull { it.name }
            .filter { it !in internalDevices }
            .distinct()
    }

    /**
     * Open a MIDI device by name.
     * @param deviceName Name of the device to open
     * @return true if successfully opened
     */
    fun openDevice(deviceName: String): Boolean {
        closeDevice()

        val port = midiAccess.inputs.find { it.name == deviceName }
        if (port == null) {
            log.warn { "MIDI device not found: $deviceName" }
            return false
        }

        scope.launch {
            try {
                val input = midiAccess.openInput(port.id)
                currentInput = input

                // Set up message listener
                input.setMessageReceivedListener(OnMidiReceivedEventListener { data, start, length, _ ->
                    handleMidiMessage(data, start, length)
                })

                log.debug { "Opened MIDI device: $deviceName" }
            } catch (e: Exception) {
                log.error { "Failed to open MIDI device: ${e.message}" }
            }
        }

        return true
    }

    /**
     * Close the currently open device.
     */
    fun closeDevice() {
        try {
            currentInput?.close()
        } catch (e: Exception) {
            log.warn { "Error closing MIDI device: ${e.message}" }
        }
        currentInput = null
    }

    /**
     * Start listening for MIDI events.
     */
    fun start(listener: MidiEventListener) {
        this.listener = listener
        log.debug { "Started listening for MIDI events" }
    }

    /**
     * Stop listening for MIDI events.
     */
    fun stop() {
        listener = null
    }

    /**
     * Whether a device is currently open.
     */
    val isOpen: Boolean
        get() = currentInput?.connectionState == MidiPortConnectionState.OPEN

    /**
     * Name of the currently open device, or null.
     */
    val currentDeviceName: String?
        get() = currentInput?.details?.name

    /**
     * Check if the current device is still available.
     */
    fun isCurrentDeviceAvailable(): Boolean {
        val deviceName = currentDeviceName ?: return false
        // Get fresh device list without updating the class's midiAccess
        val freshAccess = midiAccessFactory()
        return freshAccess.inputs
            .mapNotNull { it.name }
            .filter { it !in internalDevices }
            .contains(deviceName)
    }

    private fun handleMidiMessage(data: ByteArray, start: Int, length: Int) {
        val currentListener = listener ?: return
        if (length < 2) return

        val status = data[start].toInt() and 0xF0
        val channel = data[start].toInt() and 0x0F
        val data1 = if (length > 1) data[start + 1].toInt() and 0x7F else 0
        val data2 = if (length > 2) data[start + 2].toInt() and 0x7F else 0

        when (status) {
            0x90 -> { // Note On
                if (data2 > 0) {
                    currentListener.onNoteOn(data1, data2)
                } else {
                    // Note on with velocity 0 is effectively note off
                    currentListener.onNoteOff(data1)
                }
            }

            0x80 -> { // Note Off
                currentListener.onNoteOff(data1)
            }

            0xB0 -> { // Control Change
                currentListener.onControlChange(data1, data2)
            }

            0xE0 -> { // Pitch Bend
                // Pitch bend is 14-bit value: data1 = LSB, data2 = MSB
                val value = ((data2 shl 7) or data1) - 8192
                currentListener.onPitchBend(value)
            }
        }
    }
}
