package org.balch.orpheus.features.cowbell.motion

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A lock-free ring of the last [capacity] `(uptimeMillis, magnitude)` samples. One sensor thread
 * calls [record]; the UI thread calls [peak]. Each slot is a pair of atomics guarded by its time:
 * the writer blanks the time, writes the magnitude, then publishes the time, and a reader keeps a
 * slot only if the time it read before and after the magnitude agree. Allocates nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
class JoltWindow(private val capacity: Int = 64) {
    private val times = AtomicLongArray(capacity)
    private val magnitudes = AtomicLongArray(capacity)
    private val head = AtomicInt(0)

    init {
        require(capacity > 0) { "capacity must be positive" }
        clear()
    }

    /** Called from the sensor thread only. [atMillis] must not be negative. */
    fun record(atMillis: Long, magnitude: Float) {
        val slot = head.load()
        times.storeAt(slot, BLANK)
        magnitudes.storeAt(slot, magnitude.toRawBits().toLong())
        times.storeAt(slot, atMillis.coerceAtLeast(0L))
        head.store(if (slot + 1 == capacity) 0 else slot + 1)
    }

    /** When the sample [peak] last returned was recorded, or -1 when it found none. UI thread only. */
    var lastPeakAtMillis: Long = -1L
        private set

    /** The largest magnitude recorded in `[fromMillis, toMillis]`, or 0 when there is none. */
    fun peak(fromMillis: Long, toMillis: Long): Float {
        var best = 0f
        lastPeakAtMillis = -1L
        for (i in 0 until capacity) {
            val before = times.loadAt(i)
            if (before == BLANK || before < fromMillis || before > toMillis) continue
            val magnitude = Float.fromBits(magnitudes.loadAt(i).toInt())
            if (times.loadAt(i) != before) continue
            if (magnitude > best) {
                best = magnitude
                lastPeakAtMillis = before
            }
        }
        return best
    }

    /** Forgets every sample. Call it from the writer thread, or while the sensors are stopped. */
    fun clear() {
        for (i in 0 until capacity) {
            times.storeAt(i, BLANK)
            magnitudes.storeAt(i, 0L)
        }
        head.store(0)
    }

    private companion object {
        /** Below every real time, so a blank slot is outside any window. */
        const val BLANK = Long.MIN_VALUE
    }
}
