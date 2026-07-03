package org.balch.djapp.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.diamondedge.logging.logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.balch.djapp.DjAppGraphAndroid
import org.balch.orpheus.djapp.widget.DjWidgetRefresh
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerViewModel

/**
 * Observes playback / vibe / artwork / timer state and refreshes the
 * home-screen [DjWidget] on change (debounced). Plain class constructed in
 * [org.balch.djapp.DjAppApplication.onCreate] after the graph is built — not
 * DI-provided — so it can resolve features from the fully-built graph without
 * the construction-time cycle that DI accessors would hit.
 */
class DjWidgetUpdater(
    private val context: Context,
    private val graph: DjAppGraphAndroid,
) {
    private val log = logging("DjWidgetUpdater")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            // Resolving the feature flows is the only genuinely fatal step (a
            // failure means the graph isn't usable); the cold-start race is past
            // by the time Application.onCreate runs this, so give up if it throws.
            val sources = runCatching {
                val collection = graph.featureGraphHolder.featureGraph.featureCollection
                val pulsar = collection.getFeature<PulsarFeature>(PulsarViewModel::class)
                val timer = collection.getFeature<TimerFeature>(TimerViewModel::class)
                listOf(
                    graph.playbackController.state,
                    pulsar.vibeFlow,
                    graph.metadataProducer.artworkPngFlow,
                    // Only status transitions (start/pause/stop) — NOT the
                    // per-second remainingTime ticks. The countdown itself
                    // is a self-ticking Chronometer on the host, so we don't
                    // refresh the widget every second (the app process is
                    // often frozen in the background anyway).
                    timer.stateFlow.map { it.status }.distinctUntilChanged(),
                )
            }.onFailure { log.warn(it) { "widget updater could not resolve sources" } }
                .getOrNull() ?: return@launch

            // A transient RemoteViews/Binder error from updateAll() must NOT tear
            // down the collector — otherwise one throw silently stops ALL
            // background widget sync for the life of the process. Catch inside
            // the refresh lambda so the merged flow keeps collecting.
            DjWidgetRefresh.observe(sources) {
                runCatching { DjWidget().updateAll(context) }
                    .onFailure { log.warn(it) { "widget refresh failed" } }
            }
        }
    }
}
