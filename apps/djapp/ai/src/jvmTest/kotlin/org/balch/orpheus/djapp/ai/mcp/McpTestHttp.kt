package org.balch.orpheus.djapp.ai.mcp

import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raw streamable-HTTP calls, as an MCP client sends them. */
internal class McpTestHttp(private val http: HttpClient = HttpClient.newHttpClient()) {

    fun post(url: String, body: String, token: String?, session: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .apply { session?.let { header("Mcp-Session-Id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** A POST over a raw socket, since HttpClient won't set `Host`; returns the status code. */
    fun postWithHost(port: Int, host: String, body: String, token: String): Int =
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 10_000
            val bytes = body.toByteArray()
            val head = "POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n" +
                "Accept: application/json, text/event-stream\r\nAuthorization: Bearer $token\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            val status = socket.getInputStream().bufferedReader().readLine().orEmpty()
            status.split(" ").getOrNull(1)?.toIntOrNull() ?: error("no status line: '$status'")
        }

    /** `initialize` then `notifications/initialized`; returns the session id. */
    fun openSession(endpoint: McpEndpoint): String {
        val init = post(endpoint.url, INITIALIZE, endpoint.token)
        assertEquals(200, init.statusCode(), init.body())
        val session = init.headers().firstValue("Mcp-Session-Id").orElseThrow()
        val notified = post(endpoint.url, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", endpoint.token, session)
        assertTrue(notified.statusCode() in 200..299, "initialized: ${notified.statusCode()}")
        return session
    }

    /**
     * Opens the session's standalone GET stream, where the JSON-response transport sends notifications,
     * and queues each SSE `data:` message. Returns once the headers arrive; the server flushes them with
     * a priming event after registering the stream, so a notification sent later reaches it.
     */
    fun listen(endpoint: McpEndpoint, session: String): LinkedBlockingQueue<JsonObject> {
        val request = HttpRequest.newBuilder(URI(endpoint.url))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${endpoint.token}")
            .header("Mcp-Session-Id", session)
            .GET()
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).get(5, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        val queue = LinkedBlockingQueue<JsonObject>()
        thread(isDaemon = true, name = "mcp-test-sse") {
            runCatching {
                response.body().forEach { line ->
                    // The server primes the stream with an empty `data:` event.
                    val data = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()
                    if (!data.isNullOrEmpty()) queue += Json.parseToJsonElement(data).jsonObject
                }
            }
        }
        return queue
    }
}

internal fun rpc(id: Int, method: String, params: String = "{}") =
    """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

internal val INITIALIZE = rpc(
    1,
    "initialize",
    """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""",
)
