package org.balch.orpheus.core.plugin

import kotlinx.serialization.Serializable

/**
 * Metadata about a DSP plugin.
 */
@Serializable
data class PluginInfo(
    val uri: String,
    val name: String,
    val author: String,
    val version: String = "1.0.0"
)

/**
 * Interface for port symbols - each plugin defines its own enum implementing this.
 * Provides compile-time safety and auto-completion.
 */
interface PortSymbol {
    val symbol: Symbol
    val uri: String
    val displayName: String
        get() = symbol.replaceFirstChar { it.uppercase() }
    
    /** Create a PluginControlId for use with SynthController and presets */
    val controlId: PluginControlId
        get() = PluginControlId(uri, symbol)

    /** Create qualified symbol "pluginUri:symbol" for use with PortRegistry */
    fun qualifiedSymbol(pluginUri: String): String = "$pluginUri:$symbol"
}

/**
 * Control identity that combines plugin URI and port symbol.
 * 
 * This is the canonical identifier for all control routing:
 * - SynthController events
 * - MIDI CC mapping
 * - Preset storage
 * - Automation
 */
data class PluginControlId(
    val uri: String,
    val symbol: String
) {
    /** Key for preset storage, MIDI mapping, control events */
    val key: String get() = "$uri:$symbol"
    
    companion object {
        /**
         * Parse a key string back into a PluginControlId.
         * @param key Format: "pluginUri:symbol"
         * @return PluginControlId or null if invalid format
         */
        fun parse(key: String): PluginControlId? {
            val parts = key.split(":", limit = 2)
            return if (parts.size == 2) PluginControlId(parts[0], parts[1]) else null
        }
    }
}
