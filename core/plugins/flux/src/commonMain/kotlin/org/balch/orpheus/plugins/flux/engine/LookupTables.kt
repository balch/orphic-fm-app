// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles resources.
// Original: marbles/resources.cc (lut_raised_cosine, lut_sine, lut_logit)
// License: MIT

package org.balch.orpheus.plugins.flux.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Lookup tables computed at init time from mathematical formulas.
 *  Each table has 257 entries (256 + 1 guard point for interpolation). */
object LookupTables {
    /** Raised cosine: (1 - cos(x * pi)) / 2, used by LagProcessor. */
    val raisedCosine: FloatArray = FloatArray(257) { i ->
        val x = i / 256.0
        ((1.0 - cos(x * PI)) / 2.0).toFloat()
    }

    /** Sine table: sin(x * 2 * pi), general-purpose. */
    val sine: FloatArray = FloatArray(257) { i ->
        val x = i / 256.0
        sin(x * 2.0 * PI).toFloat()
    }

    /** Logit / sigmoid table: 1 / (1 + exp(-(x * 20 - 10))), used by Markov T-gen model. */
    val logit: FloatArray = FloatArray(257) { i ->
        val x = i / 256.0
        val logitVal = x * 20.0 - 10.0
        (1.0 / (1.0 + exp(-logitVal))).toFloat()
    }
}
