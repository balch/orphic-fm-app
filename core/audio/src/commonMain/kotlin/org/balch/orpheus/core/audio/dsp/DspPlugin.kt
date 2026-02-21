package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol

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
     * Set a control port value by symbol.
     * @param symbol The port symbol (e.g., "feedback", "mix", "spread")
     * @param value The typed port value
     * @return true if the port was found and set, false otherwise
     */
    fun setPortValue(symbol: Symbol, value: PortValue): Boolean = false
    
    /**
     * Get a control port value by symbol.
     * @param symbol The port symbol
     * @return The current value, or null if not found
     */
    fun getPortValue(symbol: Symbol): PortValue? = null

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
