package org.balch.orpheus.core.audio.dsp.lv2

import kotlinx.serialization.Serializable

@Serializable
data class PluginInfo(
    val uri: String,
    val name: String,
    val author: String,
    val version: String = "1.0.0"
)

/**
 * Interface for an internal LV2-style plugin.
 * 
 * Plugins process blocks of audio/events in `run()`.
 * Parameters and Audio are accessed via buffers connected to ports.
 */
interface Lv2Plugin {
    val info: PluginInfo
    val ports: List<Port>

    /**
     * Subsets of ports for easy access
     */
    val audioPorts: List<AudioPort> get() = ports.filterIsInstance<AudioPort>()
    val controlPorts: List<ControlPort> get() = ports.filterIsInstance<ControlPort>()
    val atomPorts: List<AtomPort> get() = ports.filterIsInstance<AtomPort>()

    /**
     * Initialize the plugin instance.
     * Corresponds to `instantiate` in LV2.
     * Note: Sample rate is typically passed here or available globally if needed, 
     * but strictly LV2 passes it to instantiate.
     */
    fun instantiate(sampleRate: Double) {}

    /**
     * Connect a port to a data buffer.
     * For Audio: FloatArray of nFrames
     * For Control: FloatArray of size 1 (typically) or just a pointer. 
     * In Kotlin, we might simulate pointers with a wrapper or array.
     */
    fun connectPort(index: Int, data: Any)

    /**
     * Enable processing.
     */
    fun activate() {}

    /**
     * Process a block of audio.
     */
    fun run(nFrames: Int)

    /**
     * Disable processing.
     */
    fun deactivate() {}
    
    // Extension Interfaces
    val midiPorts: List<MidiPort> get() = emptyList()
    val aiInstructions: String get() = ""
}
