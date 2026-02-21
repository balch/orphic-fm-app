package org.balch.orpheus.core.audio.dsp

import kotlinx.serialization.Serializable

/**
 * Audio interfaces that have platform-specific implementations.
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
