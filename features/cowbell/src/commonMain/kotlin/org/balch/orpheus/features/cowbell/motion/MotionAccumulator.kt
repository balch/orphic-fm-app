package org.balch.orpheus.features.cowbell.motion

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The hand-off between a sensor thread and the UI thread, in device axes. The sensor side
 * publishes gravity and adds kicks; [drain] on the UI side reads gravity, takes the kicks summed
 * since the last drain, and rotates both into display axes. Allocates nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
class MotionAccumulator {
    private val gx = AtomicInt(0)
    private val gy = AtomicInt(0)
    private val gz = AtomicInt(1.0f.toRawBits())

    /** The two kick floats packed into one long, so the pair is summed and drained atomically. */
    private val kick = AtomicLong(0L)

    /** Gravity in g, device axes. The three components land one at a time, which is fine for a slow signal. */
    fun publishGravity(x: Float, y: Float, z: Float) {
        gx.store(x.toRawBits())
        gy.store(y.toRawBits())
        gz.store(z.toRawBits())
    }

    /** Adds linear acceleration (m/s², device axes) to the sum waiting for the next [drain]. */
    fun addKick(x: Float, y: Float) {
        while (true) {
            val old = kick.load()
            val nx = Float.fromBits((old ushr 32).toInt()) + x
            val ny = Float.fromBits(old.toInt()) + y
            if (kick.compareAndSet(old, pack(nx, ny))) return
        }
    }

    fun drain(into: MotionSample, rotationQuarterTurns: Int) {
        val k = kick.exchange(0L)
        val gxd = Float.fromBits(gx.load())
        val gyd = Float.fromBits(gy.load())
        into.gravityX = displayX(gxd, gyd, rotationQuarterTurns)
        into.gravityY = displayY(gxd, gyd, rotationQuarterTurns)
        into.gravityZ = Float.fromBits(gz.load())
        val kxd = Float.fromBits((k ushr 32).toInt())
        val kyd = Float.fromBits(k.toInt())
        into.kickX = displayX(kxd, kyd, rotationQuarterTurns)
        into.kickY = displayY(kxd, kyd, rotationQuarterTurns)
    }

    fun reset() {
        publishGravity(0f, 0f, 1f)
        kick.store(0L)
    }

    private fun pack(x: Float, y: Float): Long =
        (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)
}
