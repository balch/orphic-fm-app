package org.balch.orpheus.features.pulsar

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.features.pulsar.playback.neighborVibe
import org.balch.orpheus.features.pulsar.playback.shouldRestartOnPrevious

/** What the vibe navigator chrome shows: the vibe, its neighbours, and how far into the song. */
@Immutable
data class VibeNavState(
    val currentName: String = "",
    val previousName: String? = null,
    val nextName: String? = null,
    /** positionMs / durationMs in 0..1; null until the song has a bar. */
    val progress: Float? = null,
    /** ◀ will restart [currentName] rather than go to [previousName]. */
    val previousRestarts: Boolean = false,
    /** The times behind [progress], so the chrome can run the playhead on between loop-cycle updates. */
    val positionMs: Long? = null,
    val durationMs: Long? = null,
) {
    companion object {
        val EMPTY = VibeNavState()
    }
}

/** The stub [PulsarFeature.vibeNavFlow]: one shared flow, so the default getter never allocates. */
internal val EmptyVibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(VibeNavState.EMPTY).asStateFlow()

internal fun vibeNavStateOf(names: List<String>, current: String, progress: PlaybackProgress?): VibeNavState {
    val timed = progress?.takeIf { it.durationMs > 0 }
    return VibeNavState(
        currentName = current,
        previousName = neighborVibe(names, current, -1),
        nextName = neighborVibe(names, current, 1),
        progress = timed?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) },
        previousRestarts = shouldRestartOnPrevious(progress),
        positionMs = timed?.positionMs,
        durationMs = timed?.durationMs,
    )
}
