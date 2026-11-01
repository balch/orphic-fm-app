package org.balch.orpheus.features.cowbell.motion

import kotlin.math.PI
import kotlin.math.atan2

/**
 * Rotates device-frame x and y (x right and y up the phone's top edge, as Android and CoreMotion
 * report them) into screen axes: x right, y down. [rotationQuarterTurns] is Android's
 * `Display.rotation`: 0 natural, 1 the top edge turned to the viewer's left, 2 upside down, 3 right.
 */
fun displayAxes(x: Float, y: Float, rotationQuarterTurns: Int): Pair<Float, Float> =
    displayX(x, y, rotationQuarterTurns) to displayY(x, y, rotationQuarterTurns)

/** [displayAxes] for the per-frame path, one axis at a time so nothing is boxed. */
internal fun displayX(x: Float, y: Float, rotationQuarterTurns: Int): Float =
    when (rotationQuarterTurns and 3) {
        0 -> x
        1 -> -y
        2 -> -x
        else -> y
    }

internal fun displayY(x: Float, y: Float, rotationQuarterTurns: Int): Float =
    when (rotationQuarterTurns and 3) {
        0 -> -y
        1 -> -x
        2 -> y
        else -> x
    }

/**
 * The holding angle the player settles into, so that angle reads as level. It is the average of
 * the first [samples] readings handed to [capture], so a hand still settling does not set a
 * noisy neutral that then reads as a standing tilt. Both angles are in degrees from gravity in display axes. A sample is the reaction to gravity
 * (it points up), so pitch is positive as the top edge rises toward the player and roll as the
 * right edge rises, which is a dropped left edge.
 */
class TiltNeutral(private val samples: Int = CAPTURE_SAMPLES) {
    private var pitch0 = 0f
    private var roll0 = 0f
    private var taken = 0
    private var sumX = 0f
    private var sumY = 0f
    private var sumZ = 0f
    private val mean = MotionSample()

    var captured: Boolean = false
        private set

    /** Takes one reading toward the neutral; the [samples]th makes it [captured]. Later ones are ignored. */
    fun capture(sample: MotionSample) {
        if (captured) return
        sumX += sample.gravityX
        sumY += sample.gravityY
        sumZ += sample.gravityZ
        taken++
        if (taken < samples) return
        // Average the gravity vector, not the angles, so a pose near the +-180 wrap averages well.
        mean.gravityX = sumX / taken
        mean.gravityY = sumY / taken
        mean.gravityZ = sumZ / taken
        pitch0 = absolutePitch(mean)
        roll0 = absoluteRoll(mean)
        captured = true
    }

    fun reset() {
        pitch0 = 0f
        roll0 = 0f
        taken = 0
        sumX = 0f
        sumY = 0f
        sumZ = 0f
        captured = false
    }

    fun pitchDegrees(sample: MotionSample): Float = wrap(absolutePitch(sample) - pitch0)

    fun rollDegrees(sample: MotionSample): Float = wrap(absoluteRoll(sample) - roll0)

    private fun absolutePitch(s: MotionSample): Float = degrees(atan2(-s.gravityY, s.gravityZ))

    private fun absoluteRoll(s: MotionSample): Float = degrees(atan2(s.gravityX, s.gravityZ))

    private fun degrees(radians: Float): Float = radians * (180f / PI.toFloat())

    private companion object {
        const val CAPTURE_SAMPLES = 8
    }

    private fun wrap(deg: Float): Float {
        var d = deg
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }
}

/** Structure knob offset for a pitch away from neutral: ±0.4 at 35° and beyond, 0 for a bad reading. */
fun structureOffsetFromPitch(deg: Float): Float {
    if (deg.isNaN()) return 0f
    return (deg / 35f).coerceIn(-1f, 1f) * 0.4f
}
