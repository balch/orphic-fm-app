package org.balch.orpheus.features.ai

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.di.FeatureScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One failure the in-app agent hit. [tool] names the tool for a tool or vibe failure. */
@Serializable
data class AgentErrorEntry(
    val atEpochMs: Long,
    val source: AgentErrorSource,
    val message: String,
    val tool: String? = null,
)

@Serializable
enum class AgentErrorSource {
    /** The run itself failed: an LLM/API error or an unhandled exception. */
    AGENT,
    /** A tool call threw or its arguments failed validation. */
    TOOL,
    /** pulsar_apply_vibe rejected or could not apply a vibe. */
    VIBE,
}

/**
 * The agent's recent failures, newest last, so they can be read outside the chat (the AI edition
 * serves them over MCP as `agent_errors`). Holds the last [CAPACITY]; the app's log keeps the rest.
 */
@SingleIn(FeatureScope::class)
@Inject
class AgentErrorLog {
    private val _entries = MutableStateFlow<List<AgentErrorEntry>>(emptyList())
    val entries: StateFlow<List<AgentErrorEntry>> = _entries.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun record(source: AgentErrorSource, message: String, tool: String? = null) {
        val entry = AgentErrorEntry(Clock.System.now().toEpochMilliseconds(), source, message.take(MAX_MESSAGE_CHARS), tool)
        _entries.update { (it + entry).takeLast(CAPACITY) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val CAPACITY = 100
        const val MAX_MESSAGE_CHARS = 2000
    }
}
