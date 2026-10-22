package org.balch.orpheus.features.visualizations.viz

import kotlin.math.abs
import kotlin.time.TimeSource

/** A Pulsar or bus ring's samples a second at a 512-frame block, assumed until arrivals show the rate. */
private const val DEFAULT_RATE = 94.0

/** How far a new arrival pulls the scroll each frame, and the error past which it jumps instead. */
private const val PULL = 0.08
private const val SNAP_LAGS = 6.0

/**
 * Where an even scroll's right edge sits, in a ring's samples. Polls bring samples in uneven
 * batches (one or two a 60 Hz tick from a ~94 Hz ring), so scrolling by arrival stutters. Instead
 * the edge moves at the measured rate every frame, [lagSeconds] behind the newest sample (a poll
 * and a half, so it rarely has to wait), and each arrival nudges it rather than moving it.
 *
 * Positions are relative to the first end seen ([rel]), so the engine's wrapping counts never overflow.
 */
internal class ScrollClock(private val lagSeconds: Double) {
    private var base = 0
    private var started = false
    private var lastEnd = 0
    private var lastArrival: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastFrame: TimeSource.Monotonic.ValueTimeMark? = null
    private var position = 0.0

    /** Samples a second, measured from arrivals. */
    var rate = DEFAULT_RATE
        private set

    /** [end], an absolute ring count, relative to this clock's start. */
    fun rel(end: Int): Int = end - base

    /** The right edge at frame [now], for a ring ending at [end] when read at [arrivedAt]. */
    fun position(end: Int, arrivedAt: TimeSource.Monotonic.ValueTimeMark, now: TimeSource.Monotonic.ValueTimeMark): Double {
        val lagSamples = lagSeconds * rate
        if (!started || end - lastEnd < 0) {
            // First arrival, or an engine restart counting from 0 again.
            started = true
            base = end
            lastEnd = end
            lastArrival = arrivedAt
            lastFrame = now
            position = -lagSamples
            return position
        }
        val previous = lastArrival
        if (previous != null && arrivedAt != previous) {
            val gap = (arrivedAt - previous).inWholeNanoseconds / 1e9
            val count = end - lastEnd
            if (gap > 0.005 && count in 1..200) rate += (count / gap - rate) * 0.1
            lastEnd = end
            lastArrival = arrivedAt
        }
        val frameGap = lastFrame?.let { ((now - it).inWholeNanoseconds / 1e9).coerceIn(0.0, 0.1) } ?: 0.0
        lastFrame = now
        val newest = rel(end).toDouble()
        val target = newest - lagSamples + ((now - arrivedAt).inWholeNanoseconds / 1e9) * rate
        position += frameGap * rate
        val error = target - position
        position = if (abs(error) > SNAP_LAGS * lagSamples) target else position + error * PULL
        if (position > newest) position = newest
        return position
    }
}

/**
 * The peak of [ring]'s samples in bucket [bucket] (samples `bucket * stride` until the next
 * bucket, relative to a [ScrollClock]), or 0 when the ring holds none of them. Buckets are fixed
 * to the samples, not to the ring's array, so a bucket's value never changes as the ring scrolls.
 * [signed] keeps the sample furthest from 0, sign and all, for bipolar traces.
 */
internal fun bucketPeak(ring: FloatArray, ringEnd: Int, bucket: Int, stride: Int, signed: Boolean): Float {
    val oldest = ringEnd - ring.size
    val from = maxOf(bucket * stride, oldest)
    val until = minOf(bucket * stride + stride, ringEnd)
    var peak = 0f
    for (k in from until until) {
        val v = ring[k - oldest]
        if (signed) { if (abs(v) > abs(peak)) peak = v } else if (v > peak) peak = v
    }
    return peak
}
