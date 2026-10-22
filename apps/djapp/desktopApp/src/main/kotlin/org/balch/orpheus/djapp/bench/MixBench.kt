package org.balch.orpheus.djapp.bench

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.djapp.di.MixBenchExit
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.OverviewCache
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService

/** Starts the Mix Bench when the app runs with `-Dorpheus.bench=true` (`-Pbench=true`). */
object MixBench {
    val enabled: Boolean get() = System.getProperty("orpheus.bench") == "true"

    /** Builds the server over the graph's controller, prints the URL and writes it to build/mix-bench/url.txt. */
    fun launch(graph: DjAppGraph): Handle {
        val root = repoRoot()
        val engine = graph.audioEngine
        check(engine is MixBenchTap) {
            "Mix Bench needs the C++ audio engine, but the graph's AudioEngine is " +
                "${engine::class.qualifiedName}, which is not a MixBenchTap"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Shared with the agent's mix tools; the graph started it and stops it at app exit.
        val controller = graph.mixBenchController
        val playback = graph.playbackController
        val transport = object : BenchTransport {
            override val playing: StateFlow<Boolean> = playback.state.map { it == PlaybackState.Playing }
                .stateIn(scope, SharingStarted.Eagerly, playback.state.value == PlaybackState.Playing)
            override fun play() = playback.play()
            override fun pause() = playback.pause()
        }
        val server = MixBenchServer(
            controller = controller,
            pulsar = graph.pulsarFeature,
            transport = transport,
            captures = CaptureWriter(File(root, "build/mix-bench/captures"), root),
            overview = OverviewCache(root),
            vibesDir = File(root, VIBES_DIR),
            uiDispatcher = Dispatchers.Main,
            // The AI edition captures to app storage and writes AI vibes back; without it, to the repo.
            captureService = graph.mixCaptureService.takeUnless { it is UnavailableMixCaptureService },
        )
        val url = server.start()
        println("Mix Bench: $url")
        File(root, "build/mix-bench").mkdirs()
        File(root, "build/mix-bench/url.txt").writeText(url + "\n")
        val handle = Handle(url, server, scope)
        Runtime.getRuntime().addShutdownHook(Thread({ handle.close() }, "mix-bench-shutdown"))
        return handle
    }

    /** Stops the server, then the shared controller through [MixBenchExit] (app exit only). Safe to call twice. */
    class Handle internal constructor(
        val url: String,
        private val server: MixBenchServer,
        private val scope: CoroutineScope,
    ) {
        private val closed = AtomicBoolean(false)

        /** Opens the bench page in the default browser; prints the URL if there is none. */
        fun open() {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop?.isSupported(Desktop.Action.BROWSE) == true) {
                runCatching { desktop.browse(URI(url)) }.onFailure { println("Mix Bench: $url ($it)") }
            } else {
                println("Mix Bench: $url")
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            server.stop()
            // Close runs only at app exit. The exit stops the controller once, whoever gets there first.
            val stopped = MixBenchExit.runIfCreated()
            scope.cancel()
            println(if (stopped) "Mix Bench: stopped" else "Mix Bench: stop timed out")
        }
    }

    // The Gradle run task points orpheus.dsp.sourceDir at <repo>/liborpheus_dsp.
    private fun repoRoot(): File {
        val dsp = System.getProperty("orpheus.dsp.sourceDir") ?: error(
            "Mix Bench needs the repo checkout: launch it with ./gradlew :apps:djapp:desktopApp:run -Pbench=true " +
                "(orpheus.dsp.sourceDir is not set)",
        )
        val root = File(dsp).absoluteFile.parentFile
        check(File(root, VIBES_DIR).isDirectory) { "Mix Bench: no vibes under $root; is orpheus.dsp.sourceDir right?" }
        return root
    }

    private const val VIBES_DIR = "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes"
}
