package org.balch.orpheus.core.audio.dsp.lv2

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
