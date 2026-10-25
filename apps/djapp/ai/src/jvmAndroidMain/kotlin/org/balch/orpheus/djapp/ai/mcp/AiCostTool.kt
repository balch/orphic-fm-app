package org.balch.orpheus.djapp.ai.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.AiPrices
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.djapp.ai.usage.AiCostLedger
import org.balch.orpheus.djapp.ai.usage.AiCostRecord
import org.balch.orpheus.djapp.ai.usage.SongModelCost
import org.balch.orpheus.djapp.ai.usage.rollUp

@Serializable
data class AiCostArgs(
    @property:LLMDescription("Only this vibe's rows, matched by name ignoring case. Omit for every song.")
    val song: String? = null,
    @property:LLMDescription("How many of the most recent rows to return, newest last. Default 20.")
    val recent: Int = 20,
)

@Serializable
data class AiCostResult(
    val pricesAsOf: String,
    val totalUsd: Double,
    val bySongModel: List<SongModelCost>,
    val recent: List<AiCostRecord>,
)

/**
 * What the in-app agent has spent, per song per model, from [AiCostLedger]. Served by [OrphicMcpServer] only, never
 * handed to the in-app agent.
 */
class AiCostTool(private val ledger: AiCostLedger) : ToolProvider {
    override val tool: Tool<AiCostArgs, AiCostResult> by lazy {
        object : Tool<AiCostArgs, AiCostResult>(
            argsType = typeToken<AiCostArgs>(),
            resultType = typeToken<AiCostResult>(),
            name = "ai_cost",
            description = """
                Estimated USD the in-app AI agent has spent, rolled up per song (the vibe playing when each turn
                ended) per model, costliest first, plus the most recent rows. Each row is one agent turn (prompt to
                reply, all its LLM calls) or one ideas refresh (source IDEAS). Tokens are what the provider reported:
                inputTokens excludes cacheReadTokens and cacheWriteTokens. Costs use the app's price table as of
                pricesAsOf; costUsd is null for a model with no price. Survives relaunch.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: AiCostArgs): AiCostResult {
                val all = ledger.read()
                val rows = args.song?.let { s -> all.filter { it.song.equals(s, ignoreCase = true) } } ?: all
                return AiCostResult(
                    pricesAsOf = AiPrices.AS_OF,
                    totalUsd = rows.sumOf { it.costUsd ?: 0.0 },
                    bySongModel = rollUp(rows),
                    recent = rows.takeLast(args.recent.coerceIn(0, MAX_RECENT)),
                )
            }
        }
    }

    private companion object {
        const val MAX_RECENT = 500
    }
}
