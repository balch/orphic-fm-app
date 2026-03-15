package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol

/** Plugin enables when mix/amount exceeds this value (hysteresis upper bound) */
const val PLUGIN_ENABLE_THRESHOLD = 0.005f

/** Plugin disables when mix/amount drops to or below this value (hysteresis lower bound) */
const val PLUGIN_DISABLE_THRESHOLD = 0.001f

/**
 * Base interface for DSP plugin modules.
 *
 * Plugins are pure state containers that expose:
 * - Control ports for parameter setting/getting
 * - Lifecycle hooks for initialization and start/stop
 *
 * All audio processing is handled by the native C++ engine.
 */
interface DspPlugin {
    val info: PluginInfo
    val ports: List<Port>

    /** Called after all plugins created to wire internal connections */
    fun initialize() {}

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

    /** Called when audio engine starts */
    fun onStart() {}

    /** Called when audio engine stops */
    fun onStop() {}

}
