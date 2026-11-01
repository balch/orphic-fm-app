package org.balch.orpheus.features.ai.usage

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.balch.orpheus.core.ai.AiTokenUsage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AiUsageMeterTest {

    private fun meta(input: Int, output: Int, vararg extra: Pair<String, Int>) = ResponseMetaInfo(
        timestamp = Instant.fromEpochMilliseconds(0),
        inputTokensCount = input,
        outputTokensCount = output,
        metadata = if (extra.isEmpty()) null else buildJsonObject { extra.forEach { (k, v) -> put(k, v) } },
    )

    @Test
    fun anthropicInputAlreadyLeavesTheCacheOut() {
        val u = tokenUsageOf(meta(500, 80, "cacheReadInputTokens" to 40_000, "cacheCreationInputTokens" to 1_200), LLMProvider.Anthropic)
        assertEquals(AiTokenUsage(inputTokens = 500, cacheReadTokens = 40_000, cacheWriteTokens = 1_200, outputTokens = 80), u)
    }

    @Test
    fun googlePromptCountIncludesTheCachedPart() {
        val u = tokenUsageOf(meta(50_000, 300, "cachedContentTokenCount" to 45_000), LLMProvider.Google)
        assertEquals(AiTokenUsage(inputTokens = 5_000, cacheReadTokens = 45_000, outputTokens = 300), u)
    }

    @Test
    fun aTurnSumsItsCallsAndReachesEverySink() {
        val seen = mutableListOf<AiTurnUsage>()
        val meter = AiUsageMeter(setOf(AiTurnSink { seen += it }), nowMs = { 42L })
        meter.onCall("claude-sonnet-5-5", LLMProvider.Anthropic, meta(1_000, 100))
        meter.onCall("claude-sonnet-5-5", LLMProvider.Anthropic, meta(2_000, 200, "cacheReadInputTokens" to 10_000))

        val turn = meter.endTurn(AiTurnOutcome.REPLIED)!!
        assertEquals(2, turn.calls)
        assertEquals(AiTokenUsage(inputTokens = 3_000, cacheReadTokens = 10_000, outputTokens = 300), turn.tokens)
        // $2 in, $0.20 cached, $10 out per MTok.
        assertTrue(abs(turn.costUsd!! - (0.006 + 0.002 + 0.003)) < 1e-9)
        assertEquals(listOf(turn), seen)
        assertEquals(42L, turn.atEpochMs)
    }

    @Test
    fun aTurnWithNoCallsRecordsNothingAndTheNextTurnStartsEmpty() {
        val seen = mutableListOf<AiTurnUsage>()
        val meter = AiUsageMeter(setOf(AiTurnSink { seen += it }), nowMs = { 0L })
        assertNull(meter.endTurn(AiTurnOutcome.STOPPED))
        meter.onCall("claude-opus-5-5", LLMProvider.Anthropic, meta(10, 1))
        meter.endTurn(AiTurnOutcome.FAILED)
        assertNull(meter.endTurn(AiTurnOutcome.REPLIED))
        assertEquals(listOf(AiTurnOutcome.FAILED), seen.map { it.outcome })
    }

    @Test
    fun anUnpricedModelLeavesTheTurnUnpriced() {
        val meter = AiUsageMeter(emptySet(), nowMs = { 0L })
        meter.onCall("mystery-model", LLMProvider.OpenAI, meta(10, 1))
        assertNull(meter.endTurn(AiTurnOutcome.REPLIED)!!.costUsd)
    }

    @Test
    fun anIdeasCallIsItsOwnRowAndLeavesThePendingTurnAlone() {
        val seen = mutableListOf<AiTurnUsage>()
        val meter = AiUsageMeter(setOf(AiTurnSink { seen += it }), nowMs = { 0L })
        meter.onCall("claude-haiku-5-5", LLMProvider.Anthropic, meta(1_000, 10))
        meter.recordCall(AiCallSource.IDEAS, "claude-haiku-5-5", LLMProvider.Anthropic, meta(300, 50))
        meter.endTurn(AiTurnOutcome.REPLIED)

        assertEquals(listOf(AiCallSource.IDEAS, AiCallSource.AGENT), seen.map { it.source })
        assertEquals(1_000, seen.last().tokens.inputTokens)
    }
}
