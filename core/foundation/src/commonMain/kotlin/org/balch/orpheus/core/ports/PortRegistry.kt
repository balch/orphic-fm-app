package org.balch.orpheus.core.ports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.ControlPort
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.plugin.PortType
import org.balch.orpheus.core.plugin.PortValue

/**
 * Registry that aggregates all DspPlugin ports for unified access.
 * 
 * Provides methods to:
 * - List all available control ports across all plugins
 * - Set/get port values by qualified symbol (pluginUri:portSymbol)
 * - Capture/restore full port state as a typed map
 */
@SingleIn(AppScope::class)
@Inject
class PortRegistry(
    private val plugins: Set<DspPlugin>
) {
    /**
     * All control ports across all plugins, keyed by qualified symbol.
     */
    val controlPorts: Map<String, Pair<DspPlugin, ControlPort>> by lazy {
        plugins.flatMap { plugin ->
            plugin.ports.filterIsInstance<ControlPort>().map { port ->
                val qualifiedSymbol = "${plugin.info.uri}:${port.symbol}"
                qualifiedSymbol to (plugin to port)
            }
        }.toMap()
    }

    /**
     * Set a port value by qualified symbol.
     * @return true if found and set
     */
    fun setPortValue(qualifiedSymbol: String, value: PortValue): Boolean {
        val (pluginUri, portSymbol) = parseQualifiedSymbol(qualifiedSymbol) ?: return false
        return plugins.find { it.info.uri == pluginUri }?.setPortValue(portSymbol, value) ?: false
    }

    /**
     * Get a port value by qualified symbol.
     */
    fun getPortValue(qualifiedSymbol: String): PortValue? {
        val (pluginUri, portSymbol) = parseQualifiedSymbol(qualifiedSymbol) ?: return null
        return plugins.find { it.info.uri == pluginUri }?.getPortValue(portSymbol)
    }

    /**
     * Capture all current port values as a typed map.
     */
    fun captureState(): Map<String, PortValue> {
        return controlPorts.mapNotNull { (symbol, _) ->
            getPortValue(symbol)?.let { symbol to it }
        }.toMap()
    }

    /**
     * Restore port values from a typed map.
     * Resets ALL ports to their plugin-defined defaults first, then applies overrides.
     * This ensures switching presets never leaves stale values from a previous preset.
     */
    fun restoreState(values: Map<String, PortValue>) {
        // Reset all ports to their plugin-defined defaults
        controlPorts.forEach { (qualifiedSymbol, pair) ->
            val (_, port) = pair
            val defaultValue = when (port.type) {
                PortType.FLOAT -> PortValue.FloatValue(port.default)
                PortType.INT -> PortValue.IntValue(port.default.toInt())
                PortType.BOOLEAN -> PortValue.BoolValue(port.default != 0f)
            }
            setPortValue(qualifiedSymbol, defaultValue)
        }
        // Apply preset overrides
        values.forEach { (symbol, value) ->
            setPortValue(symbol, value)
        }
    }

    private fun parseQualifiedSymbol(symbol: String): Pair<String, Symbol>? {
        val colonIndex = symbol.lastIndexOf(':')
        if (colonIndex < 0) return null
        return symbol.substring(0, colonIndex) to symbol.substring(colonIndex + 1)
    }
}
