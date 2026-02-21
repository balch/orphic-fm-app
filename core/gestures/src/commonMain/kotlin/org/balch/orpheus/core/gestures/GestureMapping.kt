package org.balch.orpheus.core.gestures

/**
 * Maps a gesture axis to a synth control parameter.
 * @param gesture Which gesture axis to read
 * @param targetControlKey The SynthController plugin control key (e.g., "voice:cutoff")
 * @param range Output value range after normalization
 */
data class GestureMapping(
    val gesture: GestureAxis,
    val targetControlKey: String,
    val range: ClosedFloatingPointRange<Float> = 0f..1f,
)
