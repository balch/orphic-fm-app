package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.media.PlaybackProgress

/** How far into a song ◀ restarts it instead of going back a vibe. The user tunes this by ear. */
internal const val RestartAfterMs = 5_000L

/** Music-player rule: far enough in, ◀ starts the song over. Unknown progress goes back. */
fun shouldRestartOnPrevious(progress: PlaybackProgress?): Boolean =
    progress != null && progress.positionMs >= RestartAfterMs
