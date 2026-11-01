package org.balch.orpheus.djapp.ai.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.features.ai.AgentErrorEntry
import org.balch.orpheus.features.ai.AgentErrorLog

@Serializable
data class AgentErrorsArgs(
    @property:LLMDescription("Return at most this many, newest last. Default 50.")
    val limit: Int = 50,
    @property:LLMDescription("Empty the log after reading it, so the next call shows only new failures.")
    val clear: Boolean = false,
)

@Serializable
data class AgentErrorsResult(
    val total: Int,
    val errors: List<AgentErrorEntry>,
)

/**
 * The in-app agent's recent failures for an MCP client. Served by [OrphicMcpServer] only, never
 * handed to the in-app agent, so the agent never reads its own error log.
 */
class AgentErrorsTool(private val log: AgentErrorLog) : ToolProvider {
    override val tool: Tool<AgentErrorsArgs, AgentErrorsResult> by lazy {
        object : Tool<AgentErrorsArgs, AgentErrorsResult>(
            argsType = typeToken<AgentErrorsArgs>(),
            resultType = typeToken<AgentErrorsResult>(),
            name = "agent_errors",
            description = """
                The in-app AI agent's recent failures, newest last: AGENT (an LLM or API error, or a crash
                in the run), TOOL (a tool call that threw or had invalid arguments, with the tool's name)
                and VIBE (pulsar_apply_vibe rejected or could not apply a vibe). Keeps the last
                ${AgentErrorLog.CAPACITY}. Pass clear=true to empty it after reading.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: AgentErrorsArgs): AgentErrorsResult {
                val all = log.entries.value
                if (args.clear) log.clear()
                return AgentErrorsResult(all.size, all.takeLast(args.limit.coerceIn(1, AgentErrorLog.CAPACITY)))
            }
        }
    }
}
