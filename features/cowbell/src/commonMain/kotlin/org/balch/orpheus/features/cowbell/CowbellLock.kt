package org.balch.orpheus.features.cowbell

/** Mirrors C++ `kGridPassThroughStrength`: the grid strength where LOCK just leaves zero. */
const val LOCK_FLOOR = 0.15f

/** LOCK as the grid port pair reads: off is 0, otherwise strength rescaled from [LOCK_FLOOR]..1. */
fun lockFrom(grid: Boolean, strength: Float): Float =
    if (!grid) 0f else ((strength - LOCK_FLOOR) / (1f - LOCK_FLOOR)).coerceIn(0f, 1f)

/** The GRID port and, when it should change, the GRID_STRENGTH port for a LOCK value. */
fun lockToGrid(lock: Float): Pair<Boolean, Float?> =
    if (lock <= 0f) false to null else true to (LOCK_FLOOR + (1f - LOCK_FLOOR) * lock.coerceAtMost(1f))
