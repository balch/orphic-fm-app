package org.balch.orpheus.djapp.review

import android.app.Activity
import android.app.Application
import com.diamondedge.logging.logging
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.engagement.EngagementAction
import org.balch.orpheus.core.engagement.EngagementTracker
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.review.ReviewGate
import org.balch.orpheus.features.pulsar.playback.PulsarSongEnding
import org.balch.orpheus.features.pulsar.playback.SongEndingEvent

/**
 * Drives the Google Play In-App Review flow for Orphic DJ.
 *
 * Subscribes to [EngagementTracker] and forwards each action to a pure [ReviewGate],
 * which counts engagement per action, applies the "PAUSE only after a song completed"
 * precondition (tracked from [PulsarSongEnding]), and bounds review attempts per session.
 * When the gate says to launch, this emits on [reviewTriggers]; the Activity collects
 * that and calls [launchReview].
 *
 * Every Play call is guarded: on non-Play builds (debug / sideloaded / emulator
 * without Play) it degrades to a silent no-op instead of throwing.
 */
@SingleIn(AppScope::class)
@Inject
class InAppReviewManager(
    application: Application,
    engagementTracker: EngagementTracker,
    songEnding: PulsarSongEnding,
    private val appPreferencesRepository: AppPreferencesRepository,
    scope: AppCoroutineScope,
) {
    private val manager = ReviewManagerFactory.create(application)
    private val log = logging("InAppReview")

    private val gate = ReviewGate()

    @Volatile
    private var songsCompletedThisSession = 0

    // DROP_OLDEST so a fresh trigger always survives even if the Activity collector is
    // momentarily detached (e.g. a config change) — never silently fail the newest emit.
    private val _reviewTriggers = MutableSharedFlow<Unit>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val reviewTriggers: SharedFlow<Unit> = _reviewTriggers.asSharedFlow()

    init {
        scope.launch {
            songEnding.songEndingEvents.collect { event ->
                if (event is SongEndingEvent.SongEnded) songsCompletedThisSession++
            }
        }
        scope.launch {
            engagementTracker.events.collect { action -> onEngagement(action) }
        }
    }

    // Runs only on the single [EngagementTracker.events] collector coroutine (collect
    // processes events one at a time), so [gate] — touched only here — needs no
    // synchronization even on the multi-threaded Dispatchers.Default backing
    // AppCoroutineScope. songsCompletedThisSession is written by the separate song-ending
    // collector, hence @Volatile. The AppPreferencesRepository.update transform is invoked
    // exactly once under its mutex, so driving the gate inside it is safe.
    private suspend fun onEngagement(action: EngagementAction) {
        var launch = false
        appPreferencesRepository.update { prefs ->
            val current = prefs.reviewEngagementTotals[action.name] ?: 0
            when (val outcome = gate.onEngagement(action, current, songsCompletedThisSession)) {
                is ReviewGate.Outcome.Counted -> {
                    launch = outcome.launchReview
                    prefs.copy(
                        reviewEngagementTotals =
                            prefs.reviewEngagementTotals + (action.name to outcome.newLifetimeTotal),
                    )
                }
                ReviewGate.Outcome.Ignored -> prefs
            }
        }
        if (launch) {
            _reviewTriggers.tryEmit(Unit)
            log.info { "review trigger ($action)" }
        }
    }

    /** Launch the Play in-app review flow. Safe to call on non-Play builds (no-ops). */
    suspend fun launchReview(activity: Activity) {
        runCatching {
            val reviewInfo = manager.requestReview()
            manager.launchReview(activity, reviewInfo)
        }.onFailure { log.warn(it) { "in-app review unavailable (non-Play build?)" } }
    }
}
