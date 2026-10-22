package org.balch.orpheus.djapp.bench

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.OverviewCache

/** Starts the Mix Bench when the app runs with `-Dorpheus.bench=true` (`-Pbench=true`). */
object MixBench {
    val enabled: Boolean get() = System.getProperty("orpheus.bench") == "true"

    /** Builds the controller and server, prints the URL and writes it to build/mix-bench/url.txt. */
    fun launch(graph: DjAppGraph): Handle {
        val root = repoRoot()
        val engine = graph.audioEngine
        val tap = engine as? MixBenchTap ?: error(
            "Mix Bench needs the C++ audio engine, but the graph's AudioEngine is " +
                "${engine::class.qualifiedName}, which is not a MixBenchTap",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MixBenchController(graph.pulsarFeature, graph.synthController, tap, scope, engine.sampleRate.toFloat())
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
            captures = CaptureWriter(root),
            overview = OverviewCache(root),
            vibesDir = File(root, VIBES_DIR),
            uiDispatcher = Dispatchers.Main,
        )
        scope.launch { controller.start() }
        val url = server.start()
        println("Mix Bench: $url")
        File(root, "build/mix-bench").mkdirs()
        File(root, "build/mix-bench/url.txt").writeText(url + "\n")
        val handle = Handle(controller, server, scope)
        Runtime.getRuntime().addShutdownHook(Thread({ handle.close() }, "mix-bench-shutdown"))
        return handle
    }

    /** Releases the taps, overlay and audition gains, then stops the server. Safe to call twice. */
    class Handle internal constructor(
        private val controller: MixBenchController,
        private val server: MixBenchServer,
        private val scope: CoroutineScope,
    ) {
        private val closed = AtomicBoolean(false)

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            server.stop()
            // stop() blocks ~100 ms on the native disable; bounded so a wedged lock can't hang exit.
            val stopped = runBlocking(Dispatchers.Default) { withTimeoutOrNull(2_000) { controller.stop() } } != null
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
