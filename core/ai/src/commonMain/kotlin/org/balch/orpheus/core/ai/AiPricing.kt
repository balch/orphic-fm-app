package org.balch.orpheus.core.ai

import kotlinx.serialization.Serializable

/** Tokens spent, split the way providers bill them. [inputTokens] leaves out the cache reads and writes. */
@Serializable
data class AiTokenUsage(
    val inputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    /** Everything the model read: what the prompt-length price tiers are measured on. */
    val promptTokens: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens

    operator fun plus(o: AiTokenUsage) = AiTokenUsage(
        inputTokens + o.inputTokens,
        cacheReadTokens + o.cacheReadTokens,
        cacheWriteTokens + o.cacheWriteTokens,
        outputTokens + o.outputTokens,
    )
}

/**
 * USD per million tokens. A prompt over [longPromptTokens] bills at [longPrompt] instead, for the
 * models whose price steps up with prompt length (Haiku 5.5 past 100K, Gemini Pro past 200K).
 */
data class AiModelPrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val longPromptTokens: Long? = null,
    val longPrompt: AiModelPrice? = null,
) {
    /** One LLM call's cost; tiers apply per call, so sum calls rather than pricing a turn's total. */
    fun costUsd(call: AiTokenUsage): Double {
        val p = if (longPromptTokens != null && call.promptTokens > longPromptTokens) longPrompt ?: this else this
        return (call.inputTokens * p.input + call.outputTokens * p.output +
            call.cacheReadTokens * p.cacheRead + call.cacheWriteTokens * p.cacheWrite) / 1_000_000.0
    }
}

/**
 * The one price table for the in-app agent's cost estimates, keyed by the API model id the
 * response reports. Cache writes are the 5-minute TTL rate (1.25x input), the only TTL the agent
 * uses. Gemini's `-latest` aliases float: these are the models they pointed at on [AS_OF]
 * (3.1 Pro, 3.8 Flash), so re-check them when Google swaps the alias. Flash's rate doubles on
 * 2027-01-01.
 */
object AiPrices {
    const val AS_OF = "2026-10-25"

    val byModelId: Map<String, AiModelPrice> = mapOf(
        "claude-fable-5-1" to AiModelPrice(input = 10.0, output = 50.0, cacheRead = 0.25, cacheWrite = 12.5),
        "claude-opus-5-5" to AiModelPrice(input = 4.0, output = 20.0, cacheRead = 0.20, cacheWrite = 5.0),
        "claude-sonnet-5-5" to AiModelPrice(input = 2.0, output = 10.0, cacheRead = 0.20, cacheWrite = 2.5),
        "claude-haiku-5-5" to AiModelPrice(
            input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.125,
            longPromptTokens = 100_000,
            longPrompt = AiModelPrice(input = 0.50, output = 2.50, cacheRead = 0.05, cacheWrite = 0.625),
        ),
        "gemini-pro-latest" to AiModelPrice(
            input = 2.0, output = 12.0, cacheRead = 0.20, cacheWrite = 2.0,
            longPromptTokens = 200_000,
            longPrompt = AiModelPrice(input = 4.0, output = 18.0, cacheRead = 0.40, cacheWrite = 4.0),
        ),
        "gemini-flash-latest" to AiModelPrice(input = 0.75, output = 3.75, cacheRead = 0.075, cacheWrite = 0.75),
    )

    /** Null for a model with no row, so an unpriced model reads as unknown rather than free. */
    fun costUsd(modelId: String, call: AiTokenUsage): Double? = byModelId[modelId]?.costUsd(call)
}
