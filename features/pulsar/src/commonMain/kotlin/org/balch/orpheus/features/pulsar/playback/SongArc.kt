package org.balch.orpheus.features.pulsar.playback

import kotlin.math.min
import kotlin.math.sqrt

/** Past this share of the estimated length the arc slows: the song may end well before or after it. */
const val SlowFrom = 0.68f

/** What the arc reads when the song reaches its estimated length. Tune freely against [SlowFrom]. */
const val AtEstimate = 0.87f

/** How far past [SlowFrom] the arc can creep, solved so it reads [AtEstimate] at the estimate. */
private val SlowRoom: Float = slowRoomFor(SlowFrom, AtEstimate)

/**
 * Where the slowdown heads and never reaches before the last section locks the real end in: never a
 * full ring. It follows from [SlowFrom] and [AtEstimate], about 0.916; a clamp below it would stop the arc dead.
 */
val SlowCeiling: Float = SlowFrom + SlowRoom

// Solves room * v / sqrt(1 + v^2) = at - from at the estimate, where v = (1 - from) / room.
private fun slowRoomFor(from: Float, at: Float): Float {
    require(from < at && at < 1f) { "AtEstimate ($at) must sit between SlowFrom ($from) and 1" }
    val span = 1f - from
    val ratio = span / (at - from)
    return span / sqrt(ratio * ratio - 1f)
}

/**
 * The arc for a song [x] of the way through its estimated length: [x] up to [SlowFrom], then ever
 * slower, [AtEstimate] at the estimate and ever closer to [SlowCeiling] until the last section locks
 * the real end in. A soft knee: slope 1 at [SlowFrom] and braking gently at first, harder later.
 */
fun slowedFraction(x: Float): Float {
    if (x <= SlowFrom) return x
    val v = (x - SlowFrom) / SlowRoom
    return SlowFrom + SlowRoom * v / sqrt(1f + v * v)
}

/** Once the end locks in, the arc closes on the song's real share within this much song time. */
const val CatchUpMs = 6_000f

/**
 * How much of the progress arc to draw at [positionMs], the same on every surface. Until [final] it
 * is [slowedFraction] over the song's [estimateMs], whatever length the tracker reports. Once the
 * end locks in at [lockedAtMs] it eases off the slowdown onto the song's real share within
 * [CatchUpMs] (at most half the song left), then rides that share to the end; a song still behind
 * the slowdown keeps it until it catches up. A final length with no lock (an end known from the
 * start) is the song's own share.
 */
fun songArc(positionMs: Float, durationMs: Long, final: Boolean, estimateMs: Long, lockedAtMs: Long?): Float {
    if (!final) return slowedFraction(positionMs / estimateMs)
    val real = (positionMs / durationMs).coerceIn(0f, 1f)
    if (lockedAtMs == null) return real
    val slowed = slowedFraction(positionMs / estimateMs)
    // Behind the lock (a display a moment behind the tracker) it is still the slowdown.
    if (positionMs < lockedAtMs || real <= slowed) return slowed
    val left = durationMs - lockedAtMs
    if (left <= 0L) return 1f
    val t = ((positionMs - lockedAtMs) / min(CatchUpMs, left / 2f)).coerceAtMost(1f)
    // Smoothstep: leaves the slowdown at its pace and joins the song at its pace, no kink at either end.
    return slowed + t * t * (3f - 2f * t) * (real - slowed)
}
