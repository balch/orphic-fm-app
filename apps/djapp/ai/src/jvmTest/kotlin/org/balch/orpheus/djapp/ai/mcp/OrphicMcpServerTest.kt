package org.balch.orpheus.djapp.ai.mcp

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.balch.orpheus.core.ai.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrphicMcpServerTest {

    private val dir: File = Files.createTempDirectory("orphic-mcp").toFile()
    private val servers = mutableListOf<McpHttpServer>()
    private val http = McpTestHttp()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        dir.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0, 50, LOOPBACK).use { it.localPort }

    private fun server(
        ports: IntRange = freePort().let { it..it },
        toolsContext: CoroutineContext = EmptyCoroutineContext,
        endpointState: McpEndpointState? = null,
        tools: () -> Collection<ToolProvider> = { listOf(FakeProvider(EchoTool())) },
    ) = McpHttpServer(
        McpTokenStore(dir),
        ports,
        toolsContext = toolsContext,
        mcpJsonHint = HINT,
        endpointState = endpointState,
        tools = tools,
    ).also { servers += it }

    private fun post(url: String, body: String, token: String?, session: String? = null) = http.post(url, body, token, session)

    private fun readDiscovery(): JsonObject = Json.parseToJsonElement(File(dir, "mcp.json").readText()).jsonObject

    @Test
    fun rejectsMissingOrWrongToken() {
        val asked = AtomicInteger()
        val endpoint = assertNotNull(server(tools = { asked.incrementAndGet(); listOf(FakeProvider(EchoTool())) }).start())

        val none = post(endpoint.url, INITIALIZE, token = null)
        assertEquals(401, none.statusCode())
        assertTrue(HINT in none.body(), none.body())
        val wrong = post(endpoint.url, INITIALIZE, token = "0".repeat(64))
        assertEquals(401, wrong.statusCode())
        assertEquals(0, asked.get(), "an unauthorized request must not reach the tools")

        assertEquals(200, post(endpoint.url, INITIALIZE, endpoint.token).statusCode())
    }

    @Test
    fun toolsResolveOnceOnTheToolsContext() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val threads = CopyOnWriteArrayList<String>()
            val tools = { threads += Thread.currentThread().name; listOf(FakeProvider(EchoTool())) }
            val endpoint = assertNotNull(server(toolsContext = main.asCoroutineDispatcher(), tools = tools).start())
            // A first resolve can build the FeatureGraph, so it must run on Main, not a Ktor worker.
            assertEquals(200, post(endpoint.url, INITIALIZE, endpoint.token).statusCode())
            assertEquals(200, post(endpoint.url, INITIALIZE, endpoint.token).statusCode())
            assertEquals(1, threads.size, "$threads")
            assertTrue(threads.single().startsWith("fake-main"), threads.single())
        } finally {
            main.shutdown()
        }
    }

    @Test
    fun listsAndCallsToolsOverHttp() {
        val endpoint = assertNotNull(server(tools = { listOf(FakeProvider(EchoTool()), FakeProvider(KindsTool())) }).start())

        val init = post(endpoint.url, INITIALIZE, endpoint.token)
        assertEquals(200, init.statusCode(), init.body())
        val session = init.headers().firstValue("Mcp-Session-Id").orElse(null)
        val initResult = Json.parseToJsonElement(init.body()).jsonObject.getValue("result").jsonObject
        assertEquals("orphic", initResult.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)

        val notified = post(endpoint.url, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", endpoint.token, session)
        assertTrue(notified.statusCode() in 200..299, "initialized: ${notified.statusCode()}")

        val list = post(endpoint.url, rpc(2, "tools/list"), endpoint.token, session)
        assertEquals(200, list.statusCode(), list.body())
        val tools = Json.parseToJsonElement(list.body()).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
        assertEquals(setOf("echo", "kinds"), tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())

        val call = post(endpoint.url, rpc(3, "tools/call", """{"name":"echo","arguments":{"text":"la","times":2}}"""), endpoint.token, session)
        assertEquals(200, call.statusCode(), call.body())
        val result = Json.parseToJsonElement(call.body()).jsonObject.getValue("result").jsonObject
        assertNotEquals("true", result["isError"]?.jsonPrimitive?.content)
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue("\"echoed\":\"lala\"" in text, text)

        val failed = post(endpoint.url, rpc(4, "tools/call", """{"name":"echo","arguments":{"text":"refuse"}}"""), endpoint.token, session)
        val failedResult = Json.parseToJsonElement(failed.body()).jsonObject.getValue("result").jsonObject
        assertEquals("true", failedResult.getValue("isError").jsonPrimitive.content)
    }

    @Test
    fun publishesTheEndpointOnceListening() {
        val state = McpEndpointState()
        val server = server(endpointState = state)
        assertNull(state.endpoint.value)

        val endpoint = assertNotNull(server.start())
        val onDisk = Json.decodeFromString(McpEndpoint.serializer(), File(dir, "mcp.json").readText())
        assertEquals(onDisk, state.endpoint.value)
        assertEquals(endpoint, state.endpoint.value)

        server.stop()
        assertNull(state.endpoint.value)
    }

    @Test
    fun acceptsLocalhostOnAForwardedPort() {
        val endpoint = assertNotNull(server().start())
        // `adb forward tcp:47821 tcp:<device port>` keeps the client's Host, so the port is not the bound one.
        assertNotEquals(47821, endpoint.port)
        for (host in listOf("127.0.0.1:47821", "localhost:47821")) {
            assertEquals(200, http.postWithHost(endpoint.port, host, INITIALIZE, endpoint.token), host)
        }
    }

    @Test
    fun rejectsAForeignHost() {
        val endpoint = assertNotNull(server().start())
        assertEquals(403, http.postWithHost(endpoint.port, "evil.example", INITIALIZE, endpoint.token))
        assertEquals(403, http.postWithHost(endpoint.port, "evil.example:${endpoint.port}", INITIALIZE, endpoint.token))
    }

    @Test
    fun portInUseFallsBack() {
        val taken = freePort()
        ServerSocket(taken, 50, LOOPBACK).use {
            val endpoint = assertNotNull(server(ports = taken..taken + 9).start())
            assertNotEquals(taken, endpoint.port)
            assertTrue(endpoint.port in taken + 1..taken + 9)
            assertEquals("http://127.0.0.1:${endpoint.port}/mcp", endpoint.url)
            assertEquals(endpoint.port, readDiscovery().getValue("port").jsonPrimitive.int)
            assertEquals(endpoint.url, readDiscovery().getValue("url").jsonPrimitive.content)

            // Every port taken: the app still starts, just without the server.
            assertNull(server(ports = taken..taken).start())
        }
    }

    @Test
    fun failedPortCandidateLeaksNoException() {
        runTest { } // arms kotlinx-coroutines-test's collector for uncaught coroutine exceptions
        val taken = freePort()
        ServerSocket(taken, 50, LOOPBACK).use {
            assertNotNull(server(ports = taken..taken + 9).start())
            assertNull(server(ports = taken..taken).start())
        }
        // A bind failure that escaped to the global handler fails this (empty) runTest.
        runTest { }
    }

    @Test
    fun tokenPersistsAcrossRestart() {
        val first = assertNotNull(server().start())
        assertTrue(first.token.matches(Regex("[0-9a-f]{64}")), first.token)
        servers.forEach { it.stop() }

        val second = assertNotNull(server().start())
        assertEquals(first.token, second.token)
        val discovery = readDiscovery()
        assertEquals(setOf("url", "token", "port"), discovery.keys)
        assertEquals(second.token, discovery.getValue("token").jsonPrimitive.content)
        assertEquals(second.port, discovery.getValue("port").jsonPrimitive.int)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            val perms = Files.getPosixFilePermissions(File(dir, "mcp.json").toPath())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
        }
        assertEquals(200, post(second.url, INITIALIZE, first.token).statusCode())
    }

    @Test
    fun slowToolCompletes() {
        // Real time over real HTTP: a default timeout shorter than a capture would cut this call off.
        val endpoint = assertNotNull(server(tools = { listOf(FakeProvider(SlowTool())) }).start())
        val session = http.openSession(endpoint)
        val call = post(endpoint.url, rpc(2, "tools/call", """{"name":"slow","arguments":{"text":"late","times":3}}"""), endpoint.token, session)
        assertEquals(200, call.statusCode(), call.body())
        val result = Json.parseToJsonElement(call.body()).jsonObject.getValue("result").jsonObject
        assertNotEquals("true", result["isError"]?.jsonPrimitive?.content, call.body())
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue("\"echoed\":\"late\"" in text, text)
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val HINT = "test-dir/mcp.json"
    }
}
