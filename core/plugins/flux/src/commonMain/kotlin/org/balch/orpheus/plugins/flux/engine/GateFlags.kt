// Copyright 2017 Emilie Gillet.
// Ported to Kotlin from stmlib/utils/gate_flags.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Gate flag bits for edge-tagged gate processing.
 *  A typical gate sequence: 00000000003111111111114000000000
 *  where 0=LOW, 3=RISING|HIGH, 1=HIGH, 4=FALLING */
object GateFlags {
    const val LOW: Int = 0
    const val HIGH: Int = 1
    const val RISING: Int = 2
    const val FALLING: Int = 4

    /** Extract gate flags from previous flags and current boolean state. */
    fun extract(previous: Int, current: Boolean): Int {
        val prevHigh = previous and HIGH
        return if (current) {
            if (prevHigh != 0) HIGH else (RISING or HIGH)
        } else {
            if (prevHigh != 0) FALLING else LOW
        }
    }
}
