// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles random/output_channel.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Voltage scaling and offset. */
data class ScaleOffset(val scale: Float = 1f, val offset: Float = 0f) {
    operator fun invoke(x: Float): Float = x * scale + offset
}
