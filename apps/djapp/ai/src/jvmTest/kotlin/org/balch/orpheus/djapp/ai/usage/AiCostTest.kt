package org.balch.orpheus.djapp.ai.usage

import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.ai.AiPrices
import org.balch.orpheus.core.ai.AiTokenUsage
import org.balch.orpheus.djapp.ai.DjAiFeedItem
import org.balch.orpheus.djapp.ai.DjAiPhase
import org.balch.orpheus.djapp.ai.DjAiUiState
import org.balch.orpheus.djapp.ai.chat.sessionCostLabel
import org.balch.orpheus.djapp.ai.chat.turnCostLabel
import org.balch.orpheus.djapp.ai.chat.usd
import org.balch.orpheus.djapp.ai.mcp.AiCostArgs
import org.balch.orpheus.djapp.ai.mcp.AiCostTool
import org.balch.orpheus.djapp.ai.reduceActivityEvent
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.usage.AiCallSource
import org.balch.orpheus.features.ai.usage.AiTurnOutcome
import org.balch.orpheus.features.ai.usage.AiTurnUsage
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiCostTest {

    private fun turn(model: String, cost: Double?, input: Long = 1_000, source: AiCallSource = AiCallSource.AGENT) =
        AiTurnUsage(0L, model, 1, AiTokenUsage(inputTokens = input, cacheReadTokens = 9_000, outputTokens = 200), cost, AiTurnOutcome.REPLIED, source)

    private fun tempFile(): File = File(Files.createTempDirectory("ai-usage").toFile(), "turns.jsonl")

    @Test
    fun turnsAreTaggedWithThePlayingVibeAndSurviveANewLedger() {
        val file = tempFile()
        var song = "Running Out"
        val ledger = AiCostLedger(file, AiCostTotals()) { song }
        ledger.record(turn("claude-opus-5-5", 0.05))
        song = " "
        ledger.record(turn("claude-opus-5-5", 0.02))

        val reread = AiCostLedger(file, AiCostTotals()) { "ignored" }.read()
        assertEquals(listOf("Running Out", AiCostLedger.NO_SONG), reread.map { it.song })
        assertEquals(AiPrices.AS_OF, reread.first().pricesAsOf)
        assertEquals(9_000, reread.first().cacheReadTokens)
    }

    @Test
    fun songTotalsSeedFromTheFileAndGrowWithEachTurn() {
        val file = tempFile()
        AiCostLedger(file, AiCostTotals()) { "Whale" }.record(turn("claude-opus-5-5", 0.10))

        val costs = AiCostTotals()
        val ledger = AiCostLedger(file, costs) { "Whale" }
        assertTrue(abs(costs.songs.value.getValue("Whale") - 0.10) < 1e-9)
        ledger.record(turn("claude-opus-5-5", 0.05))
        ledger.record(turn("mystery", null))
        assertTrue(abs(costs.songs.value.getValue("Whale") - 0.15) < 1e-9)
    }

    @Test
    fun sessionTotalsCountEachModelsPricedTurns() {
        val totals = AiCostTotals()
        totals.record(turn("claude-haiku-5-5", 0.02))
        totals.record(turn("claude-haiku-5-5", 0.03))
        totals.record(turn("mystery", null))
        assertEquals(setOf("claude-haiku-5-5"), totals.sessionByModel.value.keys)
        assertTrue(abs(totals.sessionByModel.value.getValue("claude-haiku-5-5") - 0.05) < 1e-9)
    }

    @Test
    fun theHeaderShowsTheModelsSpendAndATotalOnlyOnceAnotherModelSpent() {
        assertEquals(null, sessionCostLabel(emptyMap(), "claude-haiku-5-5"))
        assertEquals(" · $0.210", sessionCostLabel(mapOf("claude-haiku-5-5" to 0.21), "claude-haiku-5-5"))
        val both = mapOf("claude-haiku-5-5" to 0.21, "gemini-flash-latest" to 0.17)
        assertEquals(" · $0.170 · total $0.380", sessionCostLabel(both, "gemini-flash-latest"))
        assertEquals(" · total $0.380", sessionCostLabel(both, "claude-opus-5-5"))
    }

    @Test
    fun aBadLineIsSkippedNotFatal() {
        val file = tempFile()
        AiCostLedger(file, AiCostTotals()) { "A" }.record(turn("claude-haiku-5-5", 0.001))
        file.appendText("{not json\n")
        AiCostLedger(file, AiCostTotals()) { "A" }.record(turn("claude-haiku-5-5", 0.001))
        assertEquals(2, AiCostLedger(file, AiCostTotals()) { "A" }.read().size)
    }

    @Test
    fun rollUpSplitsSongsAndModelsAndKeepsIdeasOutOfThePerTurnAverage() {
        val rs = listOf(
            AiCostRecord.of(turn("claude-opus-5-5", 0.10), "Whale"),
            AiCostRecord.of(turn("claude-opus-5-5", 0.30), "Whale"),
            AiCostRecord.of(turn("claude-opus-5-5", 0.04, source = AiCallSource.IDEAS), "Whale"),
            AiCostRecord.of(turn("claude-sonnet-5-5", 0.05), "Whale"),
            AiCostRecord.of(turn("mystery", null), "Bubblegum"),
        )
        val rows = rollUp(rs)
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5", "mystery"), rows.map { it.modelId })

        val opus = rows.first()
        assertEquals(2, opus.turns)
        assertEquals(1, opus.ideasCalls)
        assertTrue(abs(opus.costUsd - 0.44) < 1e-9)
        assertTrue(abs(opus.ideasUsd - 0.04) < 1e-9)
        assertTrue(abs(opus.usdPerTurn - 0.20) < 1e-9)
        assertEquals(1, rows.last().unpricedRows)
    }

    @Test
    fun theMcpToolFiltersBySongIgnoringCase() = runBlocking {
        val file = tempFile()
        var song = "Whale"
        val ledger = AiCostLedger(file, AiCostTotals()) { song }
        ledger.record(turn("claude-opus-5-5", 0.10))
        song = "Bubblegum"
        ledger.record(turn("claude-opus-5-5", 0.20))

        val r = AiCostTool(ledger).tool.execute(AiCostArgs(song = "whale"))
        assertTrue(abs(r.totalUsd - 0.10) < 1e-9)
        assertEquals(listOf("Whale"), r.bySongModel.map { it.song })
    }

    @Test
    fun theReplyCarriesItsTurnCost() {
        val usage = turn("claude-opus-5-5", 0.042)
        val s = reduceActivityEvent(AgentActivityEvent.Assistant("done", usage), DjAiUiState(phase = DjAiPhase.GENERATING))
        assertEquals(usage, (s.feed.single() as DjAiFeedItem.Reply).usage)
    }

    @Test
    fun theFeedLineNamesCostTokensAndModel() {
        val u = AiTurnUsage(
            0L, "claude-opus-5-5", 3,
            AiTokenUsage(inputTokens = 2_100, cacheReadTokens = 48_000, cacheWriteTokens = 2_000, outputTokens = 1_234), 0.0421,
        )
        assertEquals("≈ $0.042 · 52.1k in (48.0k cached) · 1.2k out · Opus 5.5 · 3 calls", turnCostLabel(u))
        assertEquals("$0.0007", usd(0.00068))
        assertEquals("$1.250", usd(1.25))
        assertTrue(turnCostLabel(u.copy(costUsd = null, modelId = "x", calls = 1)).startsWith("≈ ? (no price)"))
    }
}
