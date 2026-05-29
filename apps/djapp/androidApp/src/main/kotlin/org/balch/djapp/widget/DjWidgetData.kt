package org.balch.djapp.widget

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import org.balch.djapp.DjAppApplication
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerUiState
import org.balch.orpheus.features.timer.TimerViewModel

/**
 * Resolves the live DI-graph state flows the widget renders from.
 *
 * Why flows and not a one-shot snapshot: Glance never re-runs `provideGlance`
 * on `update()`/`updateAll()` (it only recomposes the existing composition — see
 * the note on [androidx.glance.appwidget.GlanceAppWidget.provideGlance]). A value
 * captured before `provideContent` is therefore frozen for the life of the
 * session. The widget must instead *observe* these sources inside the composition
 * (collectAsState) so taps and background state changes actually re-render.
 */
object DjWidgetData {

    /** The live state flows the widget observes. The graph exposes these as
     *  process-singleton StateFlows, shared with the media session / now-playing
     *  surface, so the widget never disagrees with them. */
    class Sources(
        val title: StateFlow<String>,
        val subtitle: StateFlow<String>,
        val artworkPng: StateFlow<ByteArray?>,
        val playback: StateFlow<PlaybackState>,
        val timer: StateFlow<TimerUiState>,
    )

    /** Resolve the flows from the fully-built app graph. May throw during the
     *  cold-start race (launcher renders before features register); the caller
     *  falls back to an idle snapshot. */
    fun sources(context: Context): Sources {
        val graph = (context.applicationContext as DjAppApplication).graph
        val metadata: MetadataProducer = graph.metadataProducer
        val timer = graph.featureGraphHolder.featureGraph.featureCollection
            .getFeature<TimerFeature>(TimerViewModel::class)
        return Sources(
            title = metadata.titleFlow,        // vibe name
            subtitle = metadata.subtitleFlow,  // album title
            artworkPng = metadata.artworkPngFlow,
            playback = graph.playbackController.state,
            timer = timer.stateFlow,
        )
    }
}
