package org.balch.orpheus.features.ai.session

import kotlinx.serialization.Serializable

/**
 * Stats for a single agent round-trip (prompt -> response).
 */
@Serializable
data class AgentSessionStats(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val toolCalls: Int = 0,
)

/**
 * Cumulative session usage statistics.
 */
@Serializable
data class SessionUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val toolCalls: Int = 0,
    val sessionHistory: List<AgentSessionStats> = emptyList(),
) {
    companion object {
        val EMPTY = SessionUsage()
    }
}
