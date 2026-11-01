package org.balch.orpheus.djapp.ai.mcp

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.djapp.ai.tools.ToolProgress
import org.balch.orpheus.features.ai.TurnBudget

/**
 * Adapts koog tools to MCP: the schema comes from the koog [ToolDescriptor] and a call runs the
 * tool's own decodeArgs / execute / encodeResultToString, so each tool keeps one definition.
 */
internal object McpToolAdapter {

    // Lenient on input, explicit on output: every result field is sent, null ones are left out.
    private val serializer = KotlinxSerializer(
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        },
    )

    fun registeredTools(providers: Collection<ToolProvider>): List<RegisteredTool> =
        providers.map { provider ->
            val tool = provider.tool
            RegisteredTool(mcpTool(tool)) { request ->
                val token = request.meta?.progressToken ?: return@RegisteredTool call(tool, request.arguments)
                // The JSON-response transport sends these on the session's GET stream, if the client opened one.
                val progress = boundedProgress { done, total, message ->
                    notification(ProgressNotification(ProgressNotificationParams(token, done, total, message)))
                }
                withContext(progress) { call(tool, request.arguments) }
            }
        }

    /** A [ToolProgress] whose sends give up after [timeoutMs], so a stuck client stream never stalls the tool. */
    fun boundedProgress(
        timeoutMs: Long = PROGRESS_SEND_MS,
        send: suspend (done: Double, total: Double, message: String?) -> Unit,
    ) = ToolProgress { done, total, message -> withTimeoutOrNull(timeoutMs) { send(done, total, message) } }

    private const val PROGRESS_SEND_MS = 1_000L

    fun mcpTool(tool: ToolBase<*, *>): Tool = Tool(
        name = tool.descriptor.name,
        description = tool.descriptor.description,
        inputSchema = schema(tool.descriptor),
    )

    fun schema(descriptor: ToolDescriptor): ToolSchema {
        val params = descriptor.requiredParameters + descriptor.optionalParameters
        return ToolSchema(
            properties = JsonObject(params.associate { it.name to it.toSchema() }),
            required = descriptor.requiredParameters.map { it.name },
        )
    }

    /**
     * Runs [tool]; a throw, bad arguments or an `ok: false` result comes back as `isError`.
     * MCP has no agent turns to reset a [TurnBudget], so its calls run [TurnBudget.Exempt].
     */
    suspend fun call(tool: ToolBase<*, *>, arguments: JsonObject?): CallToolResult {
        val text = try {
            withContext(TurnBudget.Exempt) { tool.run(arguments ?: JsonObject(emptyMap())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(e.message ?: e::class.simpleName ?: "error")
        }
        if (refused(text)) return CallToolResult(content = listOf(TextContent(text)), isError = true)
        return CallToolResult(content = listOf(TextContent(text)))
    }

    private suspend fun <A, R> ToolBase<A, R>.run(arguments: JsonObject): String {
        val args = decodeArgs(arguments.toKoogJSONObject(), serializer)
        return encodeResultToString(execute(args, ToolCallMetadata.EMPTY), serializer)
    }

    private fun failure(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    private fun refused(text: String): Boolean {
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return false
        return (obj["ok"] as? JsonPrimitive)?.booleanOrNull == false
    }

    private fun ToolParameterDescriptor.toSchema(): JsonObject = type.toSchema(description)

    private fun ToolParameterType.toSchema(description: String? = null): JsonObject {
        val body: Map<String, JsonElement> = when (this) {
            ToolParameterType.String -> mapOf("type" to JsonPrimitive("string"))
            ToolParameterType.Integer -> mapOf("type" to JsonPrimitive("integer"))
            ToolParameterType.Float -> mapOf("type" to JsonPrimitive("number"))
            ToolParameterType.Boolean -> mapOf("type" to JsonPrimitive("boolean"))
            ToolParameterType.Null -> mapOf("type" to JsonPrimitive("null"))
            is ToolParameterType.Enum -> mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(entries.map(::JsonPrimitive)),
            )
            is ToolParameterType.List -> mapOf("type" to JsonPrimitive("array"), "items" to itemsType.toSchema())
            is ToolParameterType.Object -> buildMap {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(properties.associate { it.name to it.toSchema() }))
                if (requiredProperties.isNotEmpty()) put("required", JsonArray(requiredProperties.map(::JsonPrimitive)))
                additionalPropertiesType?.let { put("additionalProperties", it.toSchema()) }
                    ?: additionalProperties?.let { put("additionalProperties", JsonPrimitive(it)) }
            }
            is ToolParameterType.AnyOf -> nullableUnion() ?: mapOf("anyOf" to JsonArray(types.map { it.toSchema() }))
        }
        return if (description.isNullOrEmpty()) JsonObject(body) else JsonObject(body + ("description" to JsonPrimitive(description)))
    }

    /** `T?` arrives as anyOf(null, T); send it as T with `"type": [t, "null"]`, as koog does for Anthropic. */
    private fun ToolParameterType.AnyOf.nullableUnion(): Map<String, JsonElement>? {
        if (types.size != 2 || types.none { it.type is ToolParameterType.Null }) return null
        val inner = types.single { it.type !is ToolParameterType.Null }.type
        if (inner is ToolParameterType.AnyOf) return null
        val schema = inner.toSchema()
        val type = schema["type"]?.jsonPrimitive?.contentOrNull ?: return null
        return schema + ("type" to JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null"))))
    }
}
