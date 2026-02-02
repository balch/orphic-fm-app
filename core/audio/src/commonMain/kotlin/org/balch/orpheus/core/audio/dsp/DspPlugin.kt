package org.balch.orpheus.core.audio.dsp

import kotlinx.serialization.Serializable

/**
 * Base interface for DSP plugin modules.
 * 
 * Plugins are self-contained audio processing blocks that expose:
 * - Audio units for engine registration
 * - Named input/output ports for inter-plugin wiring
 * - Lifecycle hooks for initialization and start/stop
 */
@Serializable
data class PluginInfo(
    val uri: String,
    val name: String,
    val author: String,
    val version: String = "1.0.0"
)

/**
 * Base interface for DSP plugin modules.
 * 
 * Plugins are self-contained audio processing blocks that expose:
 * - Audio units for engine registration
 * - Named input/output ports for inter-plugin wiring
 * - Lifecycle hooks for initialization and start/stop
 */
interface DspPlugin {
    val info: PluginInfo
    val ports: List<Port>

    /** All audio units for AudioEngine registration */
    val audioUnits: List<AudioUnit>
    
    /** Named audio input ports for external connection */
    val inputs: Map<String, AudioInput>
        get() = emptyMap()
    
    /** Named audio output ports for external connection */
    val outputs: Map<String, AudioOutput>
        get() = emptyMap()
    
    /** Called after all plugins created to wire internal connections */
    fun initialize() {}

    /**
     * Connect a port to a data buffer.
     */
    fun connectPort(index: Int, data: Any)

    /**
     * Enable processing.
     */
    fun activate() {}
    
    /** Called when audio engine starts */
    fun onStart() {}

    /**
     * Process a block of audio.
     */
    fun run(nFrames: Int)
    
    /** Called when audio engine stops */
    fun onStop() {}
}
