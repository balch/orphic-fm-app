package org.balch.orpheus.plugins.cowbell

import kotlin.math.floor

private const val STRIKE_VALID = 1 shl 18

/** Round half away from zero for x >= 0, like C `lroundf`; exact in Float. */
private fun roundHalfUp(x: Float): Int {
    val f = floor(x)
    return (if (x - f >= 0.5f) f + 1f else f).toInt()
}

/**
 * Packs one strike for the cowbell `strike` port: valid(18) | kind(14..17) | color(7..13) | vel7(0..6).
 * Bit-identical to `cowbell_encode_strike` in cowbell_macros.h. Kind 0 is an engine hit.
 * Velocity clamps to 0..1: +Infinity is the loudest strike, NaN and -Infinity the softest.
 */
fun cowbellStrikeValue(velocity: Float, kind: Int = 0, color: Int = 0): Float {
    val vel = when {
        velocity.isNaN() -> 0f
        velocity == Float.POSITIVE_INFINITY -> 1f
        else -> velocity.coerceIn(0f, 1f)
    }
    val vel7 = roundHalfUp(vel * 127f).coerceAtLeast(1)
    val packed = STRIKE_VALID or
        (kind.coerceIn(0, 15) shl 14) or
        (color.coerceIn(0, 127) shl 7) or
        vel7
    return packed.toFloat()
}
