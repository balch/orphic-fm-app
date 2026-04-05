package org.balch.orpheus.features.timer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMetadata
import org.balch.orpheus.core.media.PlaybackMode

/**
 * Abstraction for updating media session metadata from the timer.
 * Decouples TimerViewModel from the platform-specific MediaSessionManager expect class,
 * making the ViewModel testable in commonTest without needing a concrete implementation.
 */
interface TimerMetadataNotifier {
    fun updateTimerTitle(title: String)
    fun setTimerActive(active: Boolean)
}

/**
 * Default implementation that delegates to MediaSessionManager.
 * Bound at AppScope so it shares the same MediaSessionManager instance.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class, binding = binding<TimerMetadataNotifier>())
class MediaSessionTimerMetadataNotifier(
    private val mediaSessionManager: MediaSessionManager,
    private val mediaSessionStateManager: MediaSessionStateManager,
) : TimerMetadataNotifier {
    override fun updateTimerTitle(title: String) {
        mediaSessionManager.updateMetadata(
            PlaybackMetadata(
                title = title,
                mode = PlaybackMode.USER,
                isPlaying = true,
            )
        )
    }

    override fun setTimerActive(active: Boolean) {
        mediaSessionStateManager.setTimerActive(active)
    }
}
