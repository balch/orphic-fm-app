package org.balch.orpheus.djapp.ai.mcp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** What `mcp.json` holds: where the server listens and the bearer token it wants. */
@Serializable
data class McpEndpoint(val url: String, val token: String, val port: Int)

/** The loopback MCP server's endpoint for the UI: null until it is listening, and always null on iOS. */
@SingleIn(AppScope::class)
@Inject
class McpEndpointState {
    private val state = MutableStateFlow<McpEndpoint?>(null)
    val endpoint: StateFlow<McpEndpoint?> = state.asStateFlow()

    fun publish(endpoint: McpEndpoint?) {
        state.value = endpoint
    }
}
