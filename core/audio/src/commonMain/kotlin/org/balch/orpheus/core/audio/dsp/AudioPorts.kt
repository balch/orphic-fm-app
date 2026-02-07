@file:Suppress("unused")
package org.balch.orpheus.core.audio.dsp

import kotlinx.serialization.Serializable

/**
 * Re-export types from core:plugin-api for backward compatibility.
 * New code should import from org.balch.orpheus.core.plugin directly.
 */

// Re-export all port types
typealias Port = org.balch.orpheus.core.plugin.Port
typealias AudioPort = org.balch.orpheus.core.plugin.AudioPort
typealias ControlPort = org.balch.orpheus.core.plugin.ControlPort
typealias AtomPort = org.balch.orpheus.core.plugin.AtomPort
typealias PortValue = org.balch.orpheus.core.plugin.PortValue
typealias FloatValue = org.balch.orpheus.core.plugin.PortValue.FloatValue
typealias IntValue = org.balch.orpheus.core.plugin.PortValue.IntValue
typealias BoolValue = org.balch.orpheus.core.plugin.PortValue.BoolValue
typealias PortType = org.balch.orpheus.core.plugin.PortType
typealias Symbol = org.balch.orpheus.core.plugin.Symbol

// Re-export plugin API types
typealias PluginInfo = org.balch.orpheus.core.plugin.PluginInfo
typealias PortSymbol = org.balch.orpheus.core.plugin.PortSymbol
typealias PluginControlId = org.balch.orpheus.core.plugin.PluginControlId

// Re-export DSL types
typealias PortsDsl = org.balch.orpheus.core.plugin.PortsDsl
typealias PortDef<T> = org.balch.orpheus.core.plugin.PortDef<T>
typealias FloatPortBuilder = org.balch.orpheus.core.plugin.FloatPortBuilder
typealias IntPortBuilder = org.balch.orpheus.core.plugin.IntPortBuilder
typealias BoolPortBuilder = org.balch.orpheus.core.plugin.BoolPortBuilder
typealias AudioPortBuilder = org.balch.orpheus.core.plugin.AudioPortBuilder
typealias ControlPortTypeBuilder = org.balch.orpheus.core.plugin.ControlPortTypeBuilder
typealias ControlPortTypeBuilderRaw = org.balch.orpheus.core.plugin.ControlPortTypeBuilderRaw
typealias PortsBuilder = org.balch.orpheus.core.plugin.PortsBuilder

/** DSL entry point - delegates to plugin-api */
inline fun ports(startIndex: Int = 0, init: PortsBuilder.() -> Unit): PortsBuilder =
    org.balch.orpheus.core.plugin.ports(startIndex, init)

/**
 * Audio interfaces remain in core/audio as they have platform-specific implementations.
 */

/**
 * An input port that can receive audio signals or control values.
 */
interface AudioInput {
    /** Set a constant value on this input */
    fun set(value: Double)

    /** Disconnect all sources from this input */
    fun disconnectAll()
}

/**
 * An output port that produces audio signals.
 */
interface AudioOutput {
    /** Connect this output to an input */
    fun connect(input: AudioInput)

    /** Connect to a specific channel of a multi-channel input */
    fun connect(channel: Int, input: AudioInput, inputChannel: Int)
}

/**
 * Base interface for all audio processing units.
 */
interface AudioUnit {
    /** Primary output of this unit */
    val output: AudioOutput
}

@Serializable
data class MidiPort(
    val portIndex: Int,
    val isInput: Boolean
)
