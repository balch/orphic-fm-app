package org.balch.orpheus.core.media

/**
 * Where playback sits within the current item, for the system seek bar. Display-only.
 * [durationFinal] is false while [durationMs] is only an estimate of where the item will end.
 * [estimateMs] is the item's first estimate, which stays put while [durationMs] moves.
 * [lockedAtMs] is where [durationFinal] first came true, the point a progress arc bends from.
 */
data class PlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val durationFinal: Boolean = true,
    val estimateMs: Long = durationMs,
    val lockedAtMs: Long? = null,
)
