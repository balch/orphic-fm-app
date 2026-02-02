package org.balch.orpheus.core.audio.dsp

/**
 * Base interface for all audio signal connections.
 * Represents a point where audio signals can be read from (output) or written to (input).
 */
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
 * Control Port (Parameters).
 * Connects to a single float value.
 */
@Serializable
data class ControlPort(
    override val index: Int,
    override val symbol: String,
    override val name: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val isInput: Boolean = true,
    val isLogarithmic: Boolean = false,
    val units: String? = null
) : Port()

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

@Serializable
data class MidiPort(
    val portIndex: Int,
    val isInput: Boolean
)

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
