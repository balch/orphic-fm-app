package org.balch.orpheus.djapp.widget

import com.diamondedge.logging.logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.DjAppHost
import org.balch.orpheus.djapp.di.DjAppGraphIos
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus

/**
 * iOS mirror of the Android DjWidgetUpdater: same flows, same debounce, a
 * different sink. Android recomposes the Glance widget in-process; here the
 * snapshot is written to a file and WidgetKit is asked to reload.
 */
class IosDjWidgetUpdater(private val graph: DjAppGraphIos) {

    private val log = logging("IosDjWidgetUpdater")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastReloadMs = 0L
    private var pendingReload: Job? = null

    fun start() {
        scope.launch {
            // Resolving the feature flows is the only genuinely fatal step (a
            // failure means the graph isn't usable); bootGraph() already ran
            // every startup feature before this, so give up if it throws.
            val resolved = runCatching {
                val collection = graph.featureGraphHolder.featureGraph.featureCollection
                val pulsar = collection.getFeature(PulsarFeature::class)
                val timer = collection.getFeature(TimerFeature::class)
                val metadata = graph.metadataProducer
                val sources = listOf(
                    graph.playbackController.state,
                    pulsar.vibeFlow,
                    metadata.artworkPngFlow,
                    // Status transitions only. The countdown itself is a host-ticked
                    // Text(timerInterval:) in the widget, so per-second ticks would
                    // burn WidgetKit's refresh budget for nothing.
                    timer.stateFlow.map { it.status }.distinctUntilChanged(),
                )
                Triple(timer, metadata, sources)
            }.onFailure { log.warn(it) { "widget updater could not resolve features" } }
                .getOrNull() ?: return@launch
            val (timer, metadata, sources) = resolved

            // A throw here must not tear down the collector — otherwise one bad
            // write silently stops ALL widget sync for the life of the process.
            // Catch inside the refresh lambda so the merged flow keeps collecting.
            DjWidgetRefresh.observe(sources) {
                runCatching {
                    val timerState = timer.stateFlow.value
                    DjWidgetStore.write(
                        DjWidgetSnapshotBuilder.build(
                            currentVibe = metadata.titleFlow.value,
                            albumTitle = metadata.subtitleFlow.value,
                            vibeNames = emptyList(),
                            isPlaying = graph.playbackController.state.value == PlaybackState.Playing,
                            timerRunning = timerState.status == TimerStatus.RUNNING,
                            timerRemainingSeconds = timerState.remainingTime.inWholeSeconds,
                            timerStatus = timerState.status.name,
                            artworkPng = metadata.artworkPngFlow.value,
                        )
                    )
                    reloadThrottled()
                }.onFailure { log.warn(it) { "widget refresh failed" } }
            }
        }
    }

    /** WidgetKit's refresh budget is far stingier than Glance's; exhausting it
     *  silently stops updates for hours. Only the reload is throttled — the
     *  snapshot file above is written on every debounced change. Leading edge
     *  reloads right away; a suppressed call schedules one trailing reload so
     *  a burst's final state still reaches the widget. */
    private fun reloadThrottled() {
        val now = Clock.System.now().toEpochMilliseconds()
        val elapsed = now - lastReloadMs
        if (elapsed >= RELOAD_FLOOR_MS) {
            pendingReload?.cancel()
            pendingReload = null
            lastReloadMs = now
            DjAppHost.reloadWidgets()
            return
        }
        // Inside the floor: one deferred reload carries the burst's final state.
        // An already-scheduled job covers it, so don't stack another.
        if (pendingReload?.isActive == true) return
        pendingReload = scope.launch {
            delay(RELOAD_FLOOR_MS - elapsed)
            runCatching {
                // lastReloadMs is now written from two coroutines (this job and
                // whichever collector call runs next). Losing that race costs at
                // most one extra reload, which the floor doesn't need to prevent,
                // so no Mutex/atomic here.
                lastReloadMs = Clock.System.now().toEpochMilliseconds()
                DjAppHost.reloadWidgets()
            }.onFailure { log.warn(it) { "deferred widget reload failed" } }
        }
    }

    private companion object {
        const val RELOAD_FLOOR_MS = 5_000L
    }
}
