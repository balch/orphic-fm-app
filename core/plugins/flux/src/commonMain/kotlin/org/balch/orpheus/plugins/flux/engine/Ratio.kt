// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles ramp/ramp_divider.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Clock ratio (p/q) for ramp division/multiplication. */
data class Ratio(var p: Int, var q: Int) {
    fun toFloat(): Float = p.toFloat() / q.toFloat()

    /** Simplify ratio by dividing p and q by [n] while both are divisible. */
    fun simplify(n: Int) {
        while (p % n == 0 && q % n == 0) {
            p /= n
            q /= n
        }
    }
}
