package org.balch.djapp.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.diamondedge.logging.logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.djapp.DjAppApplication
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.timer.TimerWidgetCommand
import org.balch.orpheus.features.timer.TimerWidgetCommandBus

private val log = logging("DjWidgetActions")

private const val SKIP_SETTLE_MS = 5_000L
private const val TIMER_SETTLE_MS = 2_000L

private fun graphOf(context: Context): DjAppGraph =
    (context.applicationContext as DjAppApplication).graph

private fun timerOf(graph: DjAppGraph): TimerFeature =
    graph.featureGraphHolder.featureGraph.featureCollection
        .getFeature(TimerViewModel::class)

private fun pulsarOf(graph: DjAppGraph): PulsarFeature =
    graph.featureGraphHolder.featureGraph.featureCollection
        .getFeature(PulsarViewModel::class)

/**
 * The vibe name a skip will land on — same cycle rule as [PulsarSkipHandler].
 * Returns null when no list is available; returns the current name when the
 * cycle wraps back to it (single-vibe list), which lets the caller skip the
 * settle wait entirely instead of blocking the full timeout for a title that
 * will never change.
 */
private fun expectedSkipTarget(pulsar: PulsarFeature, direction: SkipDirection): String? {
    val names = pulsar.vibeNames
    if (names.isEmpty()) return null
    val idx = names.indexOf(pulsar.vibeFlow.value.name)
    val next = when (direction) {
        SkipDirection.NEXT -> (idx + 1).mod(names.size)
        SkipDirection.PREVIOUS -> if (idx <= 0) names.size - 1 else idx - 1
    }
    return names[next]
}

/**
 * Dispatch [skip] on the main thread, then keep the process alive only as long
 * as a vibe transition can actually change the now-playing title — wait for the
 * specific [expected] target so the refresh matches the post-transition state.
 * When the title can't change (single-vibe list, or [expected] already current),
 * return immediately rather than blocking the Glance worker for the full timeout.
 */
private suspend fun runSkip(graph: DjAppGraph, direction: SkipDirection, skip: () -> Unit) {
    val pulsar = pulsarOf(graph)
    val before = graph.metadataProducer.titleFlow.value
    val expected = expectedSkipTarget(pulsar, direction)
    withContext(Dispatchers.Main) { skip() }
    if (expected != null && expected != before) {
        withTimeoutOrNull(SKIP_SETTLE_MS) { graph.metadataProducer.titleFlow.first { it == expected } }
    }
}

/**
 * Run [block], then refresh the widget. The widget cannot reliably self-update
 * via DjWidgetUpdater while the app process is frozen in the background, so each
 * tap refreshes the widget here. Glance keeps the process alive for the
 * duration of this suspend function, so [block] can await async state (e.g. a
 * vibe transition completing) before we re-render.
 */
private suspend fun runAction(context: Context, label: String, block: suspend () -> Unit) {
    log.info { "widget action: $label" }
    runCatching {
        block()
        DjWidget().updateAll(context)
        log.info { "widget action done: $label" }
    }.onFailure { log.warn(it) { "widget action failed: $label" } }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runAction(context, "skipNext") {
            val graph = graphOf(context)
            runSkip(graph, SkipDirection.NEXT) { graph.playbackController.onSkipNext() }
        }
}

class SkipPrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runAction(context, "skipPrev") {
            val graph = graphOf(context)
            runSkip(graph, SkipDirection.PREVIOUS) { graph.playbackController.onSkipPrevious() }
        }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runAction(context, "playPause") {
            val c = graphOf(context).playbackController
            withContext(Dispatchers.Main) {
                if (c.state.value == PlaybackState.Playing) c.onPause() else c.onPlay()
            }
        }
}

class TimerStopAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runAction(context, "timerStop") {
            val timer = timerOf(graphOf(context))
            val before = timer.stateFlow.value.status
            // TimerWidgetCommandBus is a process singleton the TimerViewModel collects.
            TimerWidgetCommandBus.send(TimerWidgetCommand.STOP)
            withTimeoutOrNull(TIMER_SETTLE_MS) { timer.stateFlow.first { it.status != before } }
        }
}
