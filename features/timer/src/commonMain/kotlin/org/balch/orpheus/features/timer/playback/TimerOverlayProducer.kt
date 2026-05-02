package org.balch.orpheus.features.timer.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.playback.OverlaySubtitleProducer
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sleep-timer overlay: emits "Sleep Timer: N min remaining" while running,
 * null when idle/finished. The MediaSession subtitle only shows minutes
 * (no seconds) — the on-screen flip-clock widget handles second-level
 * precision separately. We add one second before truncating so the value
 * matches what the user sees on the visual countdown (the underlying
 * Duration ticks at second boundaries; without +1s the subtitle reads a
 * minute lower than the flip clock at the boundary instant).
 */
@SingleIn(AppScope::class)
@Inject
class TimerOverlayProducer(
    timerFeature: TimerFeature,
    scope: AppCoroutineScope,
) : OverlaySubtitleProducer {

    private fun displayMinutes(d: Duration): Long = (d + 1.seconds).inWholeMinutes

    override val overlayFlow: StateFlow<String?> =
        timerFeature.stateFlow
            // Upstream emits every second; throttle to status + display-minute
            // boundary so the format/string allocation only runs when the
            // visible value actually changes.
            .distinctUntilChangedBy { it.status to displayMinutes(it.remainingTime) }
            .map { state ->
                if (state.status == TimerStatus.RUNNING) {
                    "Sleep Timer: ${displayMinutes(state.remainingTime)} min remaining"
                } else null
            }
            .stateIn(scope, SharingStarted.Eagerly, null)
}
