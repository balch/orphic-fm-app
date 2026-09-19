package org.balch.orpheus.core.media

/** Where playback sits within the current item, for the system seek bar. Display-only. */
data class PlaybackProgress(val positionMs: Long, val durationMs: Long)
