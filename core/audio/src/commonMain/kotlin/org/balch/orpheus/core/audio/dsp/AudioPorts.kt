package org.balch.orpheus.core.audio.dsp

/**
 * Base interface for all audio signal connections.
 * Represents a point where audio signals can be read from (output) or written to (input).
 */
interface AudioPort

/**
 * An input port that can receive audio signals or control values.
 */
interface AudioInput : AudioPort {
    /** Set a constant value on this input */
    fun set(value: Double)

    /** Disconnect all sources from this input */
    fun disconnectAll()
}

/**
 * An output port that produces audio signals.
 */
interface AudioOutput : AudioPort {
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
