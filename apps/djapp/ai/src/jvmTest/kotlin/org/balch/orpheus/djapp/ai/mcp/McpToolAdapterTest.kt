package org.balch.orpheus.djapp.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.balch.orpheus.djapp.ai.tools.reportProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolAdapterTest {

    private val dir: File = Files.createTempDirectory("orphic-mcp-adapter").toFile()
    private val servers = mutableListOf<McpHttpServer>()
    private val http = McpTestHttp()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        dir.deleteRecursively()
    }

    private fun progressServer(): McpEndpoint {
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val server = McpHttpServer(McpTokenStore(dir), port..port) { listOf(FakeProvider(ProgressTool())) }
        servers += server
        return assertNotNull(server.start())
    }

    private fun callProgress(endpoint: McpEndpoint, session: String, id: Int, meta: String?) {
        val metaField = meta?.let { ""","_meta":$it""" }.orEmpty()
        val params = """{"name":"progress","arguments":{"text":"hi"}$metaField}"""
        val response = http.post(endpoint.url, rpc(id, "tools/call", params), endpoint.token, session)
        assertEquals(200, response.statusCode(), response.body())
        val result = Json.parseToJsonElement(response.body()).jsonObject.getValue("result").jsonObject
        assertNotEquals("true", result["isError"]?.jsonPrimitive?.content, response.body())
    }

    /** Takes `notifications/progress` params off [events] until [count] arrive or [waitMs] pass. */
    private fun progressFrom(events: LinkedBlockingQueue<JsonObject>, count: Int, waitMs: Long = 5_000): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        val deadline = System.nanoTime() + waitMs * 1_000_000
        while (found.size < count) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            val event = events.poll(left, TimeUnit.NANOSECONDS) ?: break
            if (event["method"]?.jsonPrimitive?.content == "notifications/progress") found += event.getValue("params").jsonObject
        }
        return found
    }

    private fun JsonObject.prop(name: String) = getValue(name).jsonObject

    private fun JsonObject.type() = getValue("type")

    @Test
    fun schemaFromDescriptor() {
        val tool = KindsTool()
        val mcp = McpToolAdapter.mcpTool(tool)
        assertEquals("kinds", mcp.name)
        assertEquals("Takes every kind.", mcp.description)

        val schema = mcp.inputSchema
        assertEquals("object", schema.type)
        assertEquals(tool.descriptor.requiredParameters.map { it.name }.toSet(), schema.required.orEmpty().toSet())
        assertTrue("name" in schema.required.orEmpty())
        assertTrue("track" !in schema.required.orEmpty())

        val props = schema.properties!!
        assertEquals(JsonPrimitive("string"), props.prop("name").type())
        assertEquals(JsonPrimitive("A name."), props.prop("name")["description"])
        assertEquals(JsonPrimitive("integer"), props.prop("count").type())
        assertEquals(JsonPrimitive("A count."), props.prop("count")["description"])
        assertEquals(JsonPrimitive("number"), props.prop("ratio").type())
        assertEquals(JsonPrimitive("boolean"), props.prop("on").type())

        val flavor = props.prop("flavor")
        assertEquals(JsonPrimitive("string"), flavor.type())
        assertEquals(listOf("SWEET", "SOUR"), flavor.getValue("enum").jsonArray.map { it.jsonPrimitive.content })

        val steps = props.prop("steps")
        assertEquals(JsonPrimitive("array"), steps.type())
        assertEquals(JsonPrimitive("integer"), steps.getValue("items").jsonObject.type())

        val inner = props.prop("inner")
        assertEquals(JsonPrimitive("object"), inner.type())
        val innerProps = inner.getValue("properties").jsonObject
        assertEquals(JsonPrimitive("number"), innerProps.prop("gain").type())
        assertEquals(JsonPrimitive("Inner gain."), innerProps.prop("gain")["description"])
        assertEquals(JsonPrimitive("array"), innerProps.prop("tags").type())
        assertEquals(JsonPrimitive("string"), innerProps.prop("tags").getValue("items").jsonObject.type())
        assertTrue(JsonPrimitive("gain") in inner.getValue("required").jsonArray)

        // A nullable optional is a type union with null, as koog sends it to Anthropic.
        val track = props.prop("track")
        assertEquals(JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))), track.type())
        assertEquals(JsonPrimitive("Optional track."), track["description"])
    }

    @Test
    fun executeRoundTrip() = runBlocking {
        val result = McpToolAdapter.call(EchoTool(), buildJsonObject { put("text", "hi"); put("times", 3) })
        assertNotEquals(true, result.isError)
        val text = (result.content.single() as TextContent).text
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("hihihi", json.getValue("echoed").jsonPrimitive.content)
        assertEquals(true, json.getValue("ok").jsonPrimitive.content.toBoolean())

        // Defaults may be omitted, as an LLM would.
        val once = McpToolAdapter.call(EchoTool(), buildJsonObject { put("text", "yo") })
        assertTrue("\"echoed\":\"yo\"" in (once.content.single() as TextContent).text)
    }

    @Test
    fun toolErrorBecomesIsError() = runBlocking {
        val thrown = McpToolAdapter.call(EchoTool(), buildJsonObject { put("text", "boom") })
        assertEquals(true, thrown.isError)
        assertTrue("boom went the tool" in (thrown.content.single() as TextContent).text)

        val refused = McpToolAdapter.call(EchoTool(), buildJsonObject { put("text", "refuse") })
        assertEquals(true, refused.isError)
        assertTrue("track 9 out of range 0-7" in (refused.content.single() as TextContent).text)

        val badArgs = McpToolAdapter.call(EchoTool(), buildJsonObject { put("times", 2) })
        assertEquals(true, badArgs.isError)
        assertTrue("text" in (badArgs.content.single() as TextContent).text)

        val noArgs = McpToolAdapter.call(EchoTool(), null)
        assertEquals(true, noArgs.isError)
    }

    @Test
    fun stuckProgressSendGivesUp() = runTest {
        var sends = 0
        val progress = McpToolAdapter.boundedProgress { _, _, _ ->
            sends++
            awaitCancellation()
        }
        withContext(progress) {
            reportProgress(0.5, 1.0)
            reportProgress(1.0, 1.0)
        }
        // Each send waited its 1 s and was dropped; the tool went on.
        assertEquals(2, sends)
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun registersEveryProvider() {
        val tools = McpToolAdapter.registeredTools(listOf(FakeProvider(EchoTool("a")), FakeProvider(KindsTool())))
        assertEquals(listOf("a", "kinds"), tools.map { it.tool.name })
    }

    @Test
    fun progressReachesTheClient() {
        val endpoint = progressServer()
        val session = http.openSession(endpoint)
        val events = http.listen(endpoint, session)

        callProgress(endpoint, session, 2, """{"progressToken":"tok-1"}""")

        val progress = progressFrom(events, 2)
        assertEquals(2, progress.size, "$progress")
        assertEquals(listOf("tok-1", "tok-1"), progress.map { it.getValue("progressToken").jsonPrimitive.content })
        assertEquals(listOf(1.0, 2.0), progress.map { it.getValue("progress").jsonPrimitive.content.toDouble() })
        assertEquals(listOf(2.0, 2.0), progress.map { it.getValue("total").jsonPrimitive.content.toDouble() })
        assertNull(progress[0]["message"])
        assertEquals("done", progress[1].getValue("message").jsonPrimitive.content)
    }

    @Test
    fun noTokenNoNotifications() {
        // No ToolProgress in the context, so reportProgress is a silent no-op.
        val direct = runBlocking { McpToolAdapter.call(ProgressTool(), buildJsonObject { put("text", "hi") }) }
        assertNotEquals(true, direct.isError)

        val endpoint = progressServer()
        val session = http.openSession(endpoint)
        val events = http.listen(endpoint, session)
        callProgress(endpoint, session, 2, meta = null)
        // The stream is ordered, so a notification from the untokened call would arrive first.
        callProgress(endpoint, session, 3, """{"progressToken":42}""")

        val progress = progressFrom(events, 3, waitMs = 2_000)
        assertEquals(listOf("42", "42"), progress.map { it.getValue("progressToken").jsonPrimitive.content }, "$progress")
    }
}
