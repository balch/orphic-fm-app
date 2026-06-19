package org.balch.orpheus.core.engagement

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** User actions that signal meaningful engagement with Orphic DJ. */
enum class EngagementAction(
    val sessionEngagement: Int = 3,
    val totalEngagement: Int = 6,
) {
    PAUSE(sessionEngagement = 7, totalEngagement = 10),
    TRANSITION_SELECT,
    TIMER_START,
    GAIN_ADJUST(sessionEngagement = 7, totalEngagement = 10),
    VIBE_SELECT(sessionEngagement = 15, totalEngagement = 20),
    INSTRUMENTS_OPEN(sessionEngagement = 10, totalEngagement = 14),
}

/**
 * App-wide bus of [EngagementAction]s. Features call [record]; the Android in-app
 * review manager subscribes to [events]. On platforms/apps with no subscriber the
 * emissions are simply dropped (replay = 0).
 */
interface EngagementTracker {
    val events: SharedFlow<EngagementAction>
    fun record(action: EngagementAction)
}

/**
 * Default [EngagementTracker]. Throttles repeats of the *same* action within
 * [THROTTLE] (leading-edge, per type) so a continuous control — e.g. dragging a
 * gain fader, which fires many value-change callbacks — counts once. Distinct
 * action types are never throttled against each other.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultEngagementTracker internal constructor(
    private val timeSource: TimeSource,
) : EngagementTracker {

    @Inject constructor() : this(TimeSource.Monotonic)

    private val _events = MutableSharedFlow<EngagementAction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<EngagementAction> = _events.asSharedFlow()

    // Plain (unsynchronized) map: record() is called from UI callbacks and from
    // PlaybackController.pause() (the Media3 session looper) — all main-thread in practice.
    private val lastEmit = mutableMapOf<EngagementAction, TimeMark>()

    override fun record(action: EngagementAction) {
        val last = lastEmit[action]
        if (last != null && last.elapsedNow() < THROTTLE) return
        lastEmit[action] = timeSource.markNow()
        _events.tryEmit(action)
    }

    private companion object {
        val THROTTLE = 1_500.milliseconds
    }
}
