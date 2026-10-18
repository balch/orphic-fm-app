package org.balch.orpheus.features.record.playback

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.record.store.TakeInfo

@Immutable
data class PlaybackState(
    val takeId: String? = null,
    val playing: Boolean = false,
    val positionSec: Float = 0f,
    val durationSec: Float = 0f,
)

/** Plays finished takes straight from disk, never through the synth engine. */
interface TakePlayer {
    val state: StateFlow<PlaybackState>

    /** Loads [take] if it is not the current one, otherwise resumes. */
    fun play(take: TakeInfo)
    fun pause()

    /** [fraction] is 0..1 of the loaded take. */
    fun seek(fraction: Float)
    fun stop()
}
