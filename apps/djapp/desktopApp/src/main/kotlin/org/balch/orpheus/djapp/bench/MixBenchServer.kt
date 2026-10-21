package org.balch.orpheus.djapp.bench

import com.diamondedge.logging.logging
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Overview
import org.balch.orpheus.features.pulsar.bench.OverviewCache
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.bench.findVibeSource
import org.balch.orpheus.features.pulsar.bench.sha256

/** Play and pause for the bench; the app adapts its PlaybackController. */
interface BenchTransport {
    val playing: StateFlow<Boolean>
    fun play()
    fun pause()
}

/**
 * The Mix Bench web page: static files, an SSE stream (`snapshot`, then `meters` at 20 Hz and
 * `state` on change) and `POST /cmd`. Binds to loopback only and refuses any request without the key.
 * [vibesDir] locates vibe sources for the stale check; [uiDispatcher] runs vibe loads and transport.
 */
class MixBenchServer(
    private val controller: MixBenchController,
    private val pulsar: PulsarFeature,
    private val transport: BenchTransport,
    captures: CaptureWriter,
    private val overview: OverviewCache,
    private val vibesDir: File?,
    private val port: Int = 0,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val log = logging("MixBenchServer")
    private val key = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableSetOf<SseClient>()
    private val stateTicks = Channel<Unit>(Channel.CONFLATED)
    private val commands = BenchCommands(controller, pulsar, transport, captures, uiDispatcher) { baseline() }

    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    // Per vibe name: the source hash at first load (R5), and the overview render state.
    private val baselines = mutableMapOf<String, String?>()
    private val overviews = mutableMapOf<String, OverviewState>()
    private val sources = mutableMapOf<String, File?>()
    @Volatile private var stale = false

    val address: InetSocketAddress get() = checkNotNull(http) { "not started" }.address

    /** Starts listening and returns the page URL, key included. */
    fun start(): String {
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "mix-bench-http-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        http = server
        executor = pool
        startJobs()
        val a = server.address
        return "http://${a.address.hostAddress}:${a.port}/?key=$key"
    }

    fun stop() {
        scope.cancel()
        synchronized(clients) {
            clients.forEach { it.close() }
            clients.clear()
        }
        http?.stop(0)
        executor?.shutdownNow()
        http = null
    }

    private fun handle(ex: HttpExchange) {
        try {
            if (query(ex)["key"] != key) return send(ex, 403, "text/plain", "forbidden")
            val path = ex.requestURI.path
            when {
                ex.requestMethod == "GET" && path == "/" -> page(ex)
                ex.requestMethod == "GET" && path.startsWith("/static/") -> static(ex, path.removePrefix("/static/"))
                ex.requestMethod == "GET" && path == "/events" -> events(ex)
                ex.requestMethod == "GET" && path == "/overview" -> overviewFor(ex, query(ex)["vibe"])
                ex.requestMethod == "POST" && path == "/cmd" -> cmd(ex)
                else -> send(ex, 404, "text/plain", "not found")
            }
        } catch (e: IOException) {
            log.debug { "bench request ended: ${e.message}" }
        } finally {
            ex.close()
        }
    }

    private fun page(ex: HttpExchange) {
        val html = resource("index.html")?.decodeToString() ?: return send(ex, 500, "text/plain", "index.html missing")
        send(ex, 200, "text/html; charset=utf-8", html.replace("__KEY__", key))
    }

    private fun static(ex: HttpExchange, name: String) {
        if (name !in STATIC) return send(ex, 404, "text/plain", "not found")
        val bytes = resource(name) ?: return send(ex, 404, "text/plain", "not found")
        val type = if (name.endsWith(".js")) "text/javascript; charset=utf-8" else "text/css; charset=utf-8"
        send(ex, 200, type, bytes)
    }

    // The page asks for this when state says "ready" but it never got the overview event.
    private fun overviewFor(ex: HttpExchange, vibe: String?) {
        val o = vibe?.let { name -> (synchronized(overviews) { overviews[name] } as? OverviewState.Ready)?.data }
            ?: return send(ex, 404, "application/json", error("no overview for ${vibe ?: "?"}").toString())
        send(ex, 200, "application/json", BenchJson.overview(vibe, o).toString())
    }

    private fun cmd(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().decodeToString()
        val (code, reply) = try {
            val cmd = BenchJson.json.parseToJsonElement(body).jsonObject
            200 to runBlocking { commands.run(cmd) }
        } catch (e: BenchCommandException) {
            400 to error(e.message)
        } catch (e: IllegalArgumentException) {
            400 to error(e.message)
        } catch (e: Exception) {
            log.warn { "bench command failed: $body: $e" }
            500 to error(e.toString())
        }
        send(ex, code, "application/json", reply.toString())
        stateTicks.trySend(Unit)
    }

    // One thread per stream; it writes queued events until the client goes away or the server stops.
    private fun events(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)
        val client = SseClient()
        val snapshot = event("snapshot", BenchJson.state(view(), pulsar.vibeNames).toString())
        val ov = currentOverview()
        synchronized(clients) {
            client.offer(snapshot)
            if (ov != null) client.offer(event("overview", BenchJson.overview(pulsar.vibeFlow.value.name, ov).toString()))
            clients += client
        }
        val out = ex.responseBody
        try {
            while (true) {
                val msg = client.queue.poll(15, TimeUnit.SECONDS) ?: ": ping\n\n"
                if (msg === SseClient.CLOSE) break
                out.write(msg.toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
            // The page closed or reloaded.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(clients) { clients -= client }
        }
    }

    private fun broadcast(msg: String, droppable: Boolean) {
        synchronized(clients) {
            for (c in clients) {
                if (droppable && c.queue.size > 8) continue
                if (!c.offer(msg)) c.close()
            }
        }
    }

    private fun startJobs() {
        // State pushes: every source change or command ticks the conflated channel.
        scope.launch {
            for (tick in stateTicks) {
                guard("state push") { broadcast(event("state", BenchJson.state(view()).toString()), droppable = false) }
            }
        }
        val tick = { _: Any? -> stateTicks.trySend(Unit); Unit }
        scope.launch { controller.layers.collect(tick) }
        scope.launch { controller.scope.collect(tick) }
        scope.launch { controller.muted.collect(tick) }
        scope.launch { controller.soloed.collect(tick) }
        scope.launch { controller.loopSection.collect(tick) }
        scope.launch { transport.playing.collect(tick) }
        scope.launch { pulsar.arrangementStateFlow.map { it.sectionIndex }.distinctUntilChanged().collect(tick) }
        // The app's knobs are the bench's macro baseline; a load or restore moves them after the load count.
        scope.launch { pulsar.stateFlow.map { listOf(it.energy, it.complexity, it.mood, it.space) }.distinctUntilChanged().collect(tick) }
        scope.launch { pulsar.vibeLoadCount.collect { guard("vibe load") { onVibeLoad() } } }
        // Meters at 20 Hz, only while someone listens.
        scope.launch {
            while (isActive) {
                guard("meters push") {
                    if (synchronized(clients) { clients.isNotEmpty() }) {
                        val body = BenchJson.meters(controller.meters.value, pulsar.arrangementStateFlow.value)
                        broadcast(event("meters", body), droppable = true)
                    }
                }
                delay(50)
            }
        }
        // The vibe file can change under the bench (Claude applying a capture): re-check every 2 s.
        scope.launch {
            while (isActive) {
                guard("stale check") {
                    val now = computeStale()
                    if (now != stale) {
                        stale = now
                        stateTicks.trySend(Unit)
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun onVibeLoad() {
        val name = pulsar.vibeFlow.value.name
        val hash = synchronized(baselines) { baselines.getOrPut(name) { source(name)?.let(::sha256) } }
        stale = computeStale()
        val known = synchronized(overviews) { overviews[name] }
        if (known is OverviewState.Ready) {
            // The page drops its overview on every vibe change, so a re-pick sends it again.
            broadcast(event("overview", BenchJson.overview(name, known.data).toString()), droppable = false)
        } else if (hash != null && known == null) {
            synchronized(overviews) { overviews[name] = OverviewState.Rendering }
            scope.launch {
                val o = overview.get(name, hash)
                synchronized(overviews) { overviews[name] = if (o != null) OverviewState.Ready(o) else OverviewState.Failed }
                if (o != null && pulsar.vibeFlow.value.name == name) {
                    broadcast(event("overview", BenchJson.overview(name, o).toString()), droppable = false)
                }
                stateTicks.trySend(Unit)
            }
        }
        stateTicks.trySend(Unit)
    }

    // One failed iteration is logged and skipped; the job itself keeps running.
    private inline fun guard(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "bench $what failed: $e" }
        }
    }

    private fun baseline(): String? = synchronized(baselines) { baselines[pulsar.vibeFlow.value.name] }

    private fun computeStale(): Boolean {
        val base = baseline() ?: return false
        val src = source(pulsar.vibeFlow.value.name) ?: return false
        return sha256(src) != base
    }

    // findVibeSource reads every vibe file, so look each name up once.
    private fun source(name: String): File? {
        val dir = vibesDir ?: return null
        synchronized(sources) {
            if (name !in sources) sources[name] = findVibeSource(dir, name)
            return sources[name]
        }
    }

    private fun currentOverview(): Overview? =
        (synchronized(overviews) { overviews[pulsar.vibeFlow.value.name] } as? OverviewState.Ready)?.data

    private fun view(): BenchView {
        val vibe = pulsar.vibeFlow.value
        val layers = controller.layers.value
        val ov = synchronized(overviews) { overviews[vibe.name] }
        return BenchView(
            vibe = vibe,
            section = pulsar.arrangementStateFlow.value.sectionIndex,
            layers = layers,
            scope = controller.scope.value,
            muted = controller.muted.value,
            soloed = controller.soloed.value,
            loopSection = controller.loopSection.value,
            playing = transport.playing.value,
            rows = capture(vibe, layers),
            overviewStatus = when {
                vibesDir == null -> "none"
                ov == null -> if (baseline() == null) "none" else "rendering"
                ov is OverviewState.Ready -> "ready"
                ov is OverviewState.Failed -> "failed"
                else -> "rendering"
            },
            stale = stale,
            source = source(vibe.name)?.name,
            knobs = Macro.entries.associateWith(pulsar::macroKnob),
        )
    }

    private sealed interface OverviewState {
        data object Rendering : OverviewState
        data object Failed : OverviewState
        data class Ready(val data: Overview) : OverviewState
    }

    private class SseClient {
        val queue = LinkedBlockingQueue<String>(256)
        fun offer(msg: String) = queue.offer(msg)
        fun close() {
            queue.clear()
            queue.offer(CLOSE)
        }

        companion object {
            // Compared by identity, so no real event can match it.
            val CLOSE = String(charArrayOf('\u0000'))
        }
    }

    private companion object {
        val STATIC = setOf("bench.js", "bench.css")
        val threads = AtomicInteger()

        fun event(name: String, data: String) = "event: $name\ndata: $data\n\n"

        fun error(msg: String?) = buildJsonObject {
            put("ok", false)
            put("error", msg ?: "error")
        }

        fun resource(name: String): ByteArray? =
            MixBenchServer::class.java.getResourceAsStream("/mixbench/$name")?.use { it.readBytes() }

        fun query(ex: HttpExchange): Map<String, String> =
            ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }

        fun send(ex: HttpExchange, code: Int, type: String, body: String) = send(ex, code, type, body.toByteArray())

        fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.write(body)
        }
    }
}
