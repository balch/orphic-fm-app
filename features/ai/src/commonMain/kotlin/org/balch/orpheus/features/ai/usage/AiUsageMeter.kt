package org.balch.orpheus.features.ai.usage

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.ResponseMetaInfo
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.balch.orpheus.core.ai.AiPrices
import org.balch.orpheus.core.ai.AiTokenUsage
import org.balch.orpheus.core.di.FeatureScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What spent the tokens: an agent turn, or one of the DJ app's ideas refreshes (a lone call outside the agent). */
@Serializable
enum class AiCallSource { AGENT, IDEAS }

@Serializable
enum class AiTurnOutcome {
    /** The agent replied, ending the turn. */
    REPLIED,

    /** The run failed (an API error or a crash) after spending these tokens. */
    FAILED,

    /** The run was cancelled: a stop, a model switch or a new conversation. */
    STOPPED,
}

/**
 * One agent turn, from the user's prompt to the agent's reply, summed over its LLM calls.
 * [costUsd] is the [AiPrices] estimate, null when [modelId] has no price.
 */
@Serializable
data class AiTurnUsage(
    val atEpochMs: Long,
    val modelId: String,
    val calls: Int,
    val tokens: AiTokenUsage,
    val costUsd: Double?,
    val outcome: AiTurnOutcome = AiTurnOutcome.REPLIED,
    val source: AiCallSource = AiCallSource.AGENT,
)

/** Hears every finished turn. The DJ app's AI edition records them per song; other apps contribute none. */
fun interface AiTurnSink {
    fun record(turn: AiTurnUsage)
}

@ContributesTo(FeatureScope::class)
interface AiTurnSinks {
    @Multibinds(allowEmpty = true)
    fun aiTurnSinks(): Set<AiTurnSink>
}

/**
 * One response's usage, split the same way for every provider. Anthropic's `input_tokens` already
 * leaves out the cache; Google's `promptTokenCount` includes its cached part. OpenAI reports no
 * cache split through Koog, so its cached tokens bill as plain input (an overestimate).
 */
fun tokenUsageOf(meta: ResponseMetaInfo, provider: LLMProvider): AiTokenUsage {
    val input = meta.inputTokensCount?.toLong() ?: 0L
    val read = meta.metadata.long("cacheReadInputTokens") ?: meta.metadata.long("cachedContentTokenCount") ?: 0L
    val write = meta.metadata.long("cacheCreationInputTokens") ?: 0L
    val uncached = if (provider == LLMProvider.Google) (input - read).coerceAtLeast(0L) else input
    return AiTokenUsage(uncached, read, write, meta.outputTokensCount?.toLong() ?: 0L)
}

private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

/**
 * Adds up the in-app agent's LLM calls into turns. [OrpheusAgent][org.balch.orpheus.features.ai.OrpheusAgent]
 * calls [onCall] from Koog's LLM events and [endTurn] when a turn ends; each turn with calls goes to every sink.
 */
@SingleIn(FeatureScope::class)
class AiUsageMeter internal constructor(
    private val sinks: Set<AiTurnSink>,
    private val nowMs: () -> Long,
) {
    @OptIn(ExperimentalTime::class)
    @Inject
    constructor(sinks: Set<AiTurnSink>) : this(sinks, { Clock.System.now().toEpochMilliseconds() })

    private data class Pending(val modelId: String, val calls: Int, val tokens: AiTokenUsage, val costUsd: Double?)

    private val pending = MutableStateFlow<Pending?>(null)

    fun onCall(modelId: String, provider: LLMProvider, meta: ResponseMetaInfo) {
        val call = tokenUsageOf(meta, provider)
        val cost = AiPrices.costUsd(modelId, call)
        pending.update { p ->
            if (p == null) {
                Pending(modelId, 1, call, cost)
            } else {
                // One unpriced call leaves the whole turn unpriced rather than undercounted.
                Pending(modelId, p.calls + 1, p.tokens + call, if (p.costUsd == null || cost == null) null else p.costUsd + cost)
            }
        }
    }

    /** Records a single call made outside the agent (an ideas refresh) as its own row; never joins a pending turn. */
    fun recordCall(source: AiCallSource, modelId: String, provider: LLMProvider, meta: ResponseMetaInfo): AiTurnUsage {
        val call = tokenUsageOf(meta, provider)
        val turn = AiTurnUsage(nowMs(), modelId, 1, call, AiPrices.costUsd(modelId, call), AiTurnOutcome.REPLIED, source)
        sinks.forEach { it.record(turn) }
        return turn
    }

    /** Closes the turn; null when it made no LLM call. */
    fun endTurn(outcome: AiTurnOutcome): AiTurnUsage? {
        val p = pending.getAndUpdate { null } ?: return null
        val turn = AiTurnUsage(nowMs(), p.modelId, p.calls, p.tokens, p.costUsd, outcome)
        sinks.forEach { it.record(turn) }
        return turn
    }
}
