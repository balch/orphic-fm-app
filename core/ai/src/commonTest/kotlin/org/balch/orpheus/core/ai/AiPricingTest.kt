package org.balch.orpheus.core.ai

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPricingTest {

    private fun assertUsd(expected: Double, actual: Double?) =
        assertTrue(actual != null && abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun everyPickerModelHasAPrice() {
        for (m in AiModel.entries) assertNotNull(AiPrices.byModelId[m.llmModel.id], "${m.llmModel.id} has no price row")
    }

    @Test
    fun eachKindOfTokenBillsAtItsOwnRate() {
        // Opus 5.5: 10k uncached at $4, 100k cache reads at $0.20, 2k writes at $5, 1k out at $20 (per MTok).
        val call = AiTokenUsage(inputTokens = 10_000, cacheReadTokens = 100_000, cacheWriteTokens = 2_000, outputTokens = 1_000)
        assertUsd(0.04 + 0.02 + 0.01 + 0.02, AiPrices.costUsd("claude-opus-5-5", call))
    }

    @Test
    fun aLongPromptBillsAtTheLongTierAndAShortOneDoesNot() {
        val short = AiTokenUsage(inputTokens = 100_000, outputTokens = 1_000)
        val long = AiTokenUsage(inputTokens = 100_001, outputTokens = 1_000)
        assertUsd(0.01 + 0.0005, AiPrices.costUsd("claude-haiku-5-5", short))
        assertUsd(100_001 * 0.50 / 1e6 + 0.0025, AiPrices.costUsd("claude-haiku-5-5", long))
    }

    @Test
    fun cachedTokensCountTowardThePromptTier() {
        val call = AiTokenUsage(inputTokens = 1_000, cacheReadTokens = 250_000)
        assertUsd((1_000 * 4.0 + 250_000 * 0.40) / 1e6, AiPrices.costUsd("gemini-pro-latest", call))
    }

    @Test
    fun anUnknownModelIsUnpricedNotFree() {
        assertNull(AiPrices.costUsd("some-new-model", AiTokenUsage(inputTokens = 1)))
    }
}
