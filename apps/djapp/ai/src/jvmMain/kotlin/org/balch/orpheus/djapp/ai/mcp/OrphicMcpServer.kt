package org.balch.orpheus.djapp.ai.mcp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.BindException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.di.StartupRoot
import org.balch.orpheus.core.features.FeatureGraphHolder

/**
 * Merged into the FeatureScope graph so the AppScope server can read the same tool set the
 * in-app agent gets, without `core:features` depending on `core:ai`.
 */
@ContributesTo(FeatureScope::class)
interface McpToolAccess {
    val mcpToolProviders: Set<ToolProvider>
}

/**
 * The AI edition's loopback MCP server: every koog [ToolProvider] in FeatureScope, served at
 * `http://127.0.0.1:<port>/mcp` with a bearer token. Starts with the app, stops on JVM exit.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
class OrphicMcpServer(
    holder: FeatureGraphHolder,
    scope: AppCoroutineScope,
    dispatchers: DispatcherProvider,
) {
    private val log = logging("OrphicMcpServer")

    // The tools resolve on the first authorized request, never while this root is being built, and on Main,
    // since a first resolve can build the FeatureGraph.
    private val server = McpHttpServer(McpTokenStore(McpTokenStore.defaultDir()), DEFAULT_PORTS, dispatchers.main) {
        val access = holder.featureGraph as? McpToolAccess
            ?: error("FeatureGraph does not merge McpToolAccess; is :apps:djapp:ai on the graph's classpath?")
        access.mcpToolProviders
    }

    init {
        // Off the startup thread; a failure here only costs the server, never the app.
        scope.launch(dispatchers.io) {
            runCatching { server.start() }.onFailure { e -> log.error(e) { "Orphic MCP: failed to start" } }
        }
        Runtime.getRuntime().addShutdownHook(Thread(server::stop, "orphic-mcp-stop"))
    }

    private companion object {
        val DEFAULT_PORTS = 47801..47810
    }
}

/**
 * Ktor CIO plus the SDK's streamable HTTP transport; the first free port in [ports] wins.
 * [tools] runs once, on [toolsContext], for the first authorized request; a failed resolve retries on the next.
 */
internal class McpHttpServer(
    private val tokenStore: McpTokenStore,
    private val ports: IntRange,
    private val toolsContext: CoroutineContext = EmptyCoroutineContext,
    private val tools: () -> Collection<ToolProvider>,
) {
    private val log = logging("OrphicMcpServer")

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var engineJob: Job? = null
    private var endpoint: McpEndpoint? = null

    private val toolsLock = Mutex()
    @Volatile
    private var registered: List<RegisteredTool>? = null

    /** Binds, writes `mcp.json`, and returns the endpoint; null when every port is taken or start fails. */
    @Synchronized
    fun start(): McpEndpoint? {
        if (engine != null) return endpoint
        val expected = "Bearer ${tokenStore.token}".toByteArray()
        for (port in ports) {
            // CIO runs its server as a root coroutine; this parent and handler keep a failed bind
            // out of the global uncaught-exception handler.
            val job = SupervisorJob()
            val candidate = CoroutineScope(job + engineFailures(port)).embeddedServer(CIO, port = port, host = HOST) {
                mcpModule(expected)
            }
            try {
                candidate.start(wait = false)
            } catch (e: Exception) {
                discard(candidate, job)
                val cause = e.rootCause()
                if (cause is BindException) {
                    log.info { "Orphic MCP: port $port unavailable (${cause.message})" }
                    continue
                }
                log.error(e) { "Orphic MCP: server failed to start on port $port" }
                return null
            }
            val published = try {
                tokenStore.publish(port)
            } catch (e: Exception) {
                // Undiscoverable is as good as off, so don't leave it listening.
                discard(candidate, job)
                log.error(e) { "Orphic MCP: could not write mcp.json; the server is off for this launch" }
                return null
            }
            engine = candidate
            engineJob = job
            endpoint = published
            log.info { "Orphic MCP: ${published.url}" }
            println("Orphic MCP: ${published.url}")
            return published
        }
        log.warn { "Orphic MCP: no free port in $ports; the server is off for this launch" }
        return null
    }

    @Synchronized
    fun stop() {
        engine?.let { discard(it, engineJob, gracePeriodMillis = 100, timeoutMillis = 1_000) }
        engine = null
        engineJob = null
        endpoint = null
    }

    private fun Application.mcpModule(expected: ByteArray) {
        intercept(ApplicationCallPipeline.Plugins) {
            val given = call.request.header(HttpHeaders.Authorization).orEmpty().toByteArray()
            if (!MessageDigest.isEqual(expected, given)) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respondText("Missing or wrong bearer token; see ~/.config/orpheus-dj/mcp.json", status = HttpStatusCode.Unauthorized)
                finish()
                return@intercept
            }
            // The session factory below can't suspend, so the tools resolve here first.
            resolveTools()
        }
        mcpStreamableHttp(path = PATH) { newSession() }
    }

    /** A bind failure is already handled by [start]; anything else the engine throws is logged here. */
    private fun engineFailures(port: Int) = CoroutineExceptionHandler { _, e ->
        if (e.rootCause() is BindException) {
            log.debug { "Orphic MCP: port $port engine stopped after a failed bind" }
        } else {
            log.warn(e) { "Orphic MCP: engine error on port $port" }
        }
    }

    private fun discard(
        candidate: EmbeddedServer<*, *>,
        job: Job?,
        gracePeriodMillis: Long = 0,
        timeoutMillis: Long = 0,
    ) {
        runCatching { candidate.stop(gracePeriodMillis, timeoutMillis) }
        job?.cancel()
    }

    private suspend fun resolveTools(): List<RegisteredTool> = registered ?: toolsLock.withLock {
        registered ?: withContext(toolsContext) { McpToolAdapter.registeredTools(tools()) }.also { registered = it }
    }

    private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()

    private fun newSession() = Server(
        serverInfo = Implementation(name = "orphic", version = "1", title = "Orphic DJ"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = INSTRUCTIONS,
    ) {
        addTools(checkNotNull(registered) { "MCP tools not resolved before the session" })
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/mcp"
        const val INSTRUCTIONS = "Orphic DJ's live mix, port, vibe, transport and capture tools, the same ones its " +
            "in-app agent uses. Start with mix_read."
    }
}
