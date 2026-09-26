package org.balch.orpheus.features.pulsar.playback

import kotlin.math.exp
import kotlin.math.min

/** Past this share of the estimated length the arc slows: the song may end well before or after it. */
const val SlowFrom = 0.68f

/** What the arc reads when the song reaches its estimated length. Tune freely against [SlowFrom]. */
const val AtEstimate = 0.85f

/** How far past [SlowFrom] the arc can creep, solved so it reads [AtEstimate] at the estimate. */
private val SlowRoom: Float = slowRoomFor(SlowFrom, AtEstimate)

/**
 * Where the slowdown heads and never reaches before the last section locks the real end in: never a
 * full ring. It follows from [SlowFrom] and [AtEstimate], about 0.903; a clamp below it would stop the arc dead.
 */
val SlowCeiling: Float = SlowFrom + SlowRoom

// Bisects room * (1 - e^(-(1 - from) / room)) = at - from; the left side rises with room toward 1 - from.
private fun slowRoomFor(from: Float, at: Float): Float {
    require(from < at && at < 1f) { "AtEstimate ($at) must sit between SlowFrom ($from) and 1" }
    val target = (at - from).toDouble()
    val span = (1f - from).toDouble()
    fun reach(room: Double) = room * (1.0 - exp(-span / room))
    var lo = 0.0
    var hi = 1.0
    while (reach(hi) < target) hi *= 2.0
    repeat(60) { val mid = (lo + hi) / 2.0; if (reach(mid) < target) lo = mid else hi = mid }
    return hi.toFloat()
}

/**
 * The arc for a song [x] of the way through its estimated length: [x] up to [SlowFrom], then ever
 * slower, [AtEstimate] at the estimate and ever closer to [SlowCeiling] until the last section locks
 * the real end in. Its slope is 1 where the slowdown starts and falls smoothly from there, so there is no kink.
 */
fun slowedFraction(x: Float): Float {
    if (x <= SlowFrom) return x
    return SlowFrom + SlowRoom * (1f - exp(-(x - SlowFrom) / SlowRoom))
}

/** [slowedFraction]'s slope at [x]: how fast the arc moves against the song there. */
private fun slowedSlope(x: Float): Float = if (x <= SlowFrom) 1f else exp(-(x - SlowFrom) / SlowRoom)

/**
 * How much of the progress arc to draw at [positionMs], the same on every surface. Until [final] it
 * is [slowedFraction] over the song's [estimateMs], whatever length the tracker reports. Once the
 * end locks in at [lockedAtMs], the arc left runs over the song left from where the slowdown stood
 * and at its pace, bending to land on [durationMs] as the song does: it eases in when the end came
 * sooner than the slowdown allowed for, and off when it came later. A final length with no lock (an
 * end known from the start) is the song's own share.
 */
fun songArc(positionMs: Float, durationMs: Long, final: Boolean, estimateMs: Long, lockedAtMs: Long?): Float {
    if (!final) return slowedFraction(positionMs / estimateMs)
    if (lockedAtMs == null) return (positionMs / durationMs).coerceIn(0f, 1f)
    val lock = lockedAtMs.toFloat()
    // Behind the lock (a display a moment behind the tracker) it is still the slowdown.
    if (positionMs < lock) return slowedFraction(positionMs / estimateMs)
    val left = durationMs - lock
    if (left <= 0f) return 1f
    val lockFraction = slowedFraction(lock / estimateMs)
    // The old pace, capped at twice the average so a long end eases off to a stop and never backs up.
    val rate = min(slowedSlope(lock / estimateMs) / estimateMs, 2f * (1f - lockFraction) / left)
    // The quadratic from that pace that covers the arc left in the song left.
    val bend = ((1f - lockFraction) - rate * left) / (left * left)
    // Written from the end, so the last frames round toward 1 and never back off it.
    val s = durationMs - positionMs
    return (1f - s * (rate + 2f * bend * left - bend * s)).coerceIn(0f, 1f)
}
