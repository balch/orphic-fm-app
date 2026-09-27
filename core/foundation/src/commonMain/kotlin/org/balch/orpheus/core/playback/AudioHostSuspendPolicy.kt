package org.balch.orpheus.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.AudioHostSuspender
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Decides when to park the platform audio host: backgrounded and not playing, for at least [grace],
 * retried every [grace] while that holds. Foregrounding resumes unless another app is playing.
 * Talks only to [AudioHostSuspender]; no playback/session side effects live here.
 */
class AudioHostSuspendPolicy(
    private val suspender: AudioHostSuspender,
    private val scope: CoroutineScope,
    private val grace: Duration = 30.seconds,
) {
    fun start(playback: StateFlow<PlaybackState>, foreground: StateFlow<Boolean>): Job =
        scope.launch {
            combine(playback, foreground) { s, fg -> s to fg }.collectLatest { (s, fg) ->
                when {
                    fg -> suspender.resumeHostIfIdle()
                    // Retried, since a TTS veto or a TTS resume would otherwise leave the host
                    // rendering silence. A park on an already-parked host is a no-op.
                    s != PlaybackState.Playing -> {
                        while (true) {
                            delay(grace)
                            suspender.suspendHost { playback.value != PlaybackState.Playing && !foreground.value }
                        }
                    }
                    else -> Unit
                }
            }
        }
}
