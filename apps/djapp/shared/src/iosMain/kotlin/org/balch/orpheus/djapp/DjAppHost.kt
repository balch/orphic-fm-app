package org.balch.orpheus.djapp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.djapp.di.DjAppGraphIos
import org.balch.orpheus.djapp.widget.IosDjWidgetUpdater
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerWidgetCommand
import org.balch.orpheus.features.timer.TimerWidgetCommandBus

/**
 * Process-level owner of the iOS DI graph. Android keeps the graph on
 * DjAppApplication; iOS previously held it in a Compose `remember`, so a
 * background launch (a widget intent) reached no graph at all, and any second
 * construction site would have built a second audio engine.
 *
 * [bootGraph] is main-thread-only in practice; its one real caller is
 * `AppDelegate.didFinishLaunchingWithOptions`, which runs before any widget intent
 * can reach `perform` — that call is just an idempotent no-op. [startAudio] is safe
 * from any dispatcher — a mutex serializes concurrent callers.
 */
object DjAppHost {

    private val log = logging("DjAppHost")
    private val audioMutex = Mutex()

    private var graph: DjAppGraphIos? = null
    private var audioStarted = false
    private var widgetReloader: (() -> Unit)? = null

    private const val SETTLE_MS = 2_000L

    val isGraphAlive: Boolean get() = graph != null

    /** Idempotent. Builds the graph and its startup features. Starts no audio. */
    fun bootGraph() {
        if (graph != null) return
        val built = createGraphFactory<DjAppGraphIos.Factory>().create()
        built.startupInitializer.run()
        graph = built
        IosDjWidgetUpdater(built).start()
        log.info { "graph booted" }
    }

    /**
     * Idempotent. Starts the audio engine and applies the DJ reverb voicing.
     * Activating the audio session interrupts other apps' audio, so this is
     * called only when the user actually asked for sound.
     */
    suspend fun startAudio() {
        val g = graph ?: error("startAudio() before bootGraph()")
        audioMutex.withLock {
            if (audioStarted) return
            g.startDjAudio()
            // Latch only after success: a failed session activation must stay retryable.
            audioStarted = true
        }
        log.info { "audio started" }
    }

    fun requireGraph(): DjAppGraphIos = graph ?: error("graph not booted")

    /** Swift injects WidgetCenter.reloadAllTimelines here; WidgetKit is invisible to Kotlin. */
    fun setWidgetReloader(reloader: () -> Unit) {
        widgetReloader = reloader
    }

    internal fun reloadWidgets() {
        runCatching { widgetReloader?.invoke() }
            .onFailure { log.warn(it) { "widget reload failed" } }
    }

    /**
     * Runs a widget transport action. Only `play` activates audio: activating the
     * session interrupts other apps, so pausing, skipping, or stopping the timer
     * while nothing is playing must stay silent.
     */
    suspend fun perform(action: String) {
        bootGraph() // idempotent; already ran from didFinishLaunching on every real launch
        if (action == "play") {
            startAudio() // idempotent; the only path that activates the audio session
        } else if (!audioStarted) {
            // Read outside audioMutex — a benign race, same class as lastReloadMs: worst
            // case is one wasted no-op or one extra startAudio() attempt, and startAudio()
            // itself is mutex-guarded and idempotent.
            return
        }
        val graph = requireGraph()
        val controller = graph.playbackController
        val before = graph.metadataProducer.titleFlow.value
        when (action) {
            "play" -> controller.play()
            "pause" -> controller.pause()
            "skipNext" -> { controller.onSkipNext(); awaitVibeChange(before) }
            "skipPrev" -> {
                // A restart keeps the title, so waiting for it to change would block the full SETTLE_MS.
                val restarts = pulsarFeature()?.vibeNavFlow?.value?.previousRestarts == true
                controller.onSkipPrevious()
                if (!restarts) awaitVibeChange(before)
            }
            "timerStop" -> {
                val previousStatus = timerFeature()?.stateFlow?.value?.status
                TimerWidgetCommandBus.send(TimerWidgetCommand.STOP)
                if (previousStatus != null) awaitTimerStatusChange(previousStatus)
            }
        }
        // Always reload, success or timeout, so the widget never keeps a stale frame.
        reloadWidgets()
    }

    /**
     * Waits for the vibe to change before returning, so the reload doesn't
     * capture the pre-skip title. Capped well under the system's intent
     * execution window — Android can afford 5 s here, an AppIntent cannot.
     */
    private suspend fun awaitVibeChange(from: String) {
        withTimeoutOrNull(SETTLE_MS) {
            requireGraph().metadataProducer.titleFlow.first { it != from }
        }
    }

    /** Waits for the stop to land before reloading, so the chip stops ticking. */
    private suspend fun awaitTimerStatusChange(from: TimerStatus) {
        withTimeoutOrNull(SETTLE_MS) {
            timerFeature()?.stateFlow?.first { it.status != from }
        }
    }

    /** Resolves TimerFeature; null on a cold-start race, never throws into perform(). */
    private fun timerFeature(): TimerFeature? =
        runCatching {
            requireGraph().featureGraphHolder.featureGraph.featureCollection.getFeature(TimerFeature::class)
        }.onFailure { log.warn(it) { "timer feature unavailable" } }.getOrNull()

    /** Resolves PulsarFeature; null on a cold-start race, like [timerFeature]. */
    private fun pulsarFeature(): PulsarFeature? =
        runCatching {
            requireGraph().featureGraphHolder.featureGraph.featureCollection.getFeature(PulsarFeature::class)
        }.onFailure { log.warn(it) { "pulsar feature unavailable" } }.getOrNull()
}
