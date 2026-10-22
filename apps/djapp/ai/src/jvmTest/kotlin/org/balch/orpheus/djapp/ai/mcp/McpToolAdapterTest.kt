package org.balch.orpheus.djapp.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpToolAdapterTest {

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
    fun registersEveryProvider() {
        val tools = McpToolAdapter.registeredTools(listOf(FakeProvider(EchoTool("a")), FakeProvider(KindsTool())))
        assertEquals(listOf("a", "kinds"), tools.map { it.tool.name })
    }
}
