package org.balch.orpheus.core.audio.dsp

/**
 * Provides the binary ODWG wiring graph data for the DSP engine.
 * Each app provides its own implementation to define the DSP topology.
 */
fun interface WiringGraphProvider {
    fun buildGraph(): ByteArray
}
