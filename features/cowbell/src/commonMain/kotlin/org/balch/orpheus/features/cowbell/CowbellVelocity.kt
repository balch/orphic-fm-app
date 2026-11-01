package org.balch.orpheus.features.cowbell

/** Pointer speed that strikes at full velocity. */
private const val FULL_VELOCITY_SPEED = 1500f

/** A still click or touch is 0.6 and a fast swipe is 1.0, so a plain tap sounds at a playing level. */
fun velocityFromPointerSpeed(dpPerSecond: Float): Float {
    val speed = if (dpPerSecond.isNaN()) 0f else dpPerSecond
    return 0.6f + 0.4f * (speed / FULL_VELOCITY_SPEED).coerceIn(0f, 1f)
}

/** The quietest linear acceleration (m/s²) that counts as a jolt, and the one that is a full hit. */
private const val JOLT_FLOOR = 0.15f
private const val JOLT_FULL = 3.0f

/** A tap sounds no softer than this, however light the jolt and finger. */
private const val TAP_FLOOR = 0.45f

/** The accelerometer jolt of a tap, 0..1. NaN is no jolt. */
fun joltNorm(peakMs2: Float): Float {
    if (peakMs2.isNaN()) return 0f
    return ((peakMs2 - JOLT_FLOOR) / (JOLT_FULL - JOLT_FLOOR)).coerceIn(0f, 1f)
}

/**
 * The jolt of a window's [peakMs2], 0..1, or null when it measured none: a peak under the floor
 * (an empty window reads 0) is a missed spike as often as a soft tap, so it must not pin the tap
 * to the floor.
 */
fun joltOrNull(peakMs2: Float): Float? = joltNorm(peakMs2).takeIf { it > 0f }

/**
 * Judges a finger's contact size against the last [WINDOW] touches, so a small fingertip and a
 * big thumb each span 0..1. Sizes of zero or less (a panel that does not report them) are not
 * touches. Allocates nothing after construction.
 */
class ContactCalibration {
    private val sizes = FloatArray(WINDOW)
    private var count = 0
    private var head = 0

    /** [major] as 0..1 between the smallest and largest recent size, or null while that is unknown. */
    fun norm(major: Float): Float? {
        if (major.isNaN() || major <= 0f) return null
        sizes[head] = major
        head = (head + 1) % WINDOW
        if (count < WINDOW) count++
        if (count < MIN_TOUCHES) return null
        var min = sizes[0]
        var max = sizes[0]
        for (i in 1 until count) {
            val v = sizes[i]
            if (v < min) min = v
            if (v > max) max = v
        }
        if (max - min < MIN_SPREAD_PX) return null
        return ((major - min) / (max - min)).coerceIn(0f, 1f)
    }

    private companion object {
        const val WINDOW = 32
        const val MIN_TOUCHES = 4
        const val MIN_SPREAD_PX = 1f
    }
}

/** Jolt and contact size (each 0..1, or null when unmeasured) as one strength, or null with neither. */
fun fusedVelocity(jolt: Float?, size: Float?): Float? = when {
    jolt != null && size != null -> 0.7f * maxOf(jolt, size) + 0.3f * (jolt + size) / 2f
    else -> jolt ?: size
}

/** A tap's velocity: its fused strength above the floor, or the pointer's speed when nothing was measured. */
fun tapVelocity(fused: Float?, speedDpPerSecond: Float): Float =
    if (fused == null) velocityFromPointerSpeed(speedDpPerSecond) else TAP_FLOOR + (1f - TAP_FLOOR) * fused.coerceIn(0f, 1f)
