package org.balch.orpheus.core.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base class for all LV2-style ports.
 */
@Serializable
sealed class Port {
    abstract val index: Int
    abstract val symbol: String
    abstract val name: String
}

/**
 * Audio Input/Output Port.
 * Connects to a float buffer of audio samples.
 */
@Serializable
data class AudioPort(
    override val index: Int,
    override val symbol: String,
    override val name: String,
    val isInput: Boolean
) : Port()

/**
 * Serializable port value that supports multiple types.
 */
@Serializable
sealed class PortValue {
    @Serializable
    @SerialName("float")
    data class FloatValue(val value: Float) : PortValue()
    
    @Serializable
    @SerialName("int")
    data class IntValue(val value: Int) : PortValue()
    
    @Serializable
    @SerialName("bool")
    data class BoolValue(val value: Boolean) : PortValue()
    
    fun asFloat(): Float = when (this) {
        is FloatValue -> value
        is IntValue -> value.toFloat()
        is BoolValue -> if (value) 1f else 0f
    }
    
    fun asInt(): Int = when (this) {
        is FloatValue -> value.toInt()
        is IntValue -> value
        is BoolValue -> if (value) 1 else 0
    }
    
    fun asBoolean(): Boolean = when (this) {
        is FloatValue -> value > 0.5f
        is IntValue -> value != 0
        is BoolValue -> value
    }
}

/**
 * Port type enumeration for typed control ports.
 */
enum class PortType { FLOAT, INT, BOOLEAN }

/**
 * Type alias for symbol strings - allows future extension if needed.
 */
typealias Symbol = String

/**
 * Control Port (Parameters) with type information.
 * Connects to a single value of specified type.
 */
@Serializable
data class ControlPort(
    override val index: Int,
    override val symbol: String,
    override val name: String,
    val type: PortType = PortType.FLOAT,
    val default: Float,
    val min: Float,
    val max: Float,
    val isInput: Boolean = true,
    val isLogarithmic: Boolean = false,
    val units: String? = null,
    val enumLabels: List<String>? = null  // For IntPort with discrete choices
) : Port() {
    fun qualifiedSymbol(pluginUri: String): String = "$pluginUri:$symbol"
}

/**
 * Atom Port for Events (MIDI, Object updates).
 */
@Serializable
data class AtomPort(
    override val index: Int,
    override val symbol: String,
    override val name: String,
    val isInput: Boolean,
    val supportsMidi: Boolean = false
) : Port()
