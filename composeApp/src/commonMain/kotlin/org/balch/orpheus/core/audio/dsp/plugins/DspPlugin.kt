package org.balch.orpheus.core.audio.dsp.plugins

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * Base interface for DSP plugin modules.
 * 
 * Plugins are self-contained audio processing blocks that expose:
 * - Audio units for engine registration
 * - Named input/output ports for inter-plugin wiring
 * - Lifecycle hooks for initialization and start/stop
 */
interface DspPlugin {
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
    
    /** Called when audio engine starts */
    fun onStart() {}
    
    /** Called when audio engine stops */
    fun onStop() {}
}