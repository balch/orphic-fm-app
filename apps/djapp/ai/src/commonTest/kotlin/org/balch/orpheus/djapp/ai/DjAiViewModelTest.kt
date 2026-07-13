package org.balch.orpheus.djapp.ai

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure reducers in DjAiViewModel.
 *
 * Reducers are top-level internal functions so they can be called directly
 * without any DI or coroutines infrastructure.
 */
class DjAiViewModelTest {

    // ─────────────────────────────────────────────────
    // reduceActivityEvent — ToolStarted phase transitions
    // ─────────────────────────────────────────────────

    @Test
    fun toolStartedFromIdleOnVibeToolMovesToGenerating() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        assertEquals(DjAiPhase.GENERATING, s1.phase)
    }

    @Test
    fun toolStartedFromIdleOnNonVibeToolDoesNotChangePhase() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("other_tool"), s0)
        assertEquals(DjAiPhase.IDLE, s1.phase)
    }

    // ─────────────────────────────────────────────────
    // reduceActivityEvent — idle guard (stale events dropped)
    // ─────────────────────────────────────────────────

    @Test
    fun idleGuard_assistantEventDroppedWhenIdle() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.Assistant("stale message"), s0)
        assertEquals(s0, s1)
        assertEquals(emptyList<DjAiFeedItem>(), s1.feed)
    }

    @Test
    fun idleGuard_toolCompletedDroppedWhenIdle() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_get_vibe"), s0)
        assertEquals(s0, s1)
        assertEquals(emptyList<DjAiFeedItem>(), s1.feed)
    }

    @Test
    fun idleGuard_reasoningDroppedWhenIdle() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("stale thought"), s0)
        assertEquals(s0, s1)
        assertEquals(emptyList<DjAiFeedItem>(), s1.feed)
    }

    // ─────────────────────────────────────────────────
    // reduceVibeEvent
    // ─────────────────────────────────────────────────

    @Test
    fun vibeGeneratingSetsPhaseToGenerating() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE, error = "old error")
        val s1 = reduceVibeEvent(VibeCreateEvent.Generating, s0)
        assertEquals(DjAiPhase.GENERATING, s1.phase)
        assertNull(s1.error)
    }

    @Test
    fun vibeGeneratedSetsPhaseResult() {
        val vibe = makeTestVibe()
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceVibeEvent(VibeCreateEvent.Generated(vibe), s0)
        assertEquals(DjAiPhase.RESULT, s1.phase)
        assertNull(s1.error)
    }

    @Test
    fun vibeFailedSetsPhaseIdleAndError() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceVibeEvent(VibeCreateEvent.Failed("timeout"), s0)
        assertEquals(DjAiPhase.IDLE, s1.phase)
        assertEquals("timeout", s1.error)
    }

    // ─────────────────────────────────────────────────
    // reduceActivityEvent — closing events survive the GENERATING -> RESULT flip
    // ─────────────────────────────────────────────────

    @Test
    fun closingToolAndAssistantEventsProcessedAfterResultFlip() {
        // VibeApplyTool emits Generated (GENERATING -> RESULT) BEFORE Koog fires the trailing
        // ToolCompleted + Assistant. Those closing events must still be applied in RESULT.
        val generating = reduceActivityEvent(
            AgentActivityEvent.ToolStarted("pulsar_apply_vibe"),
            DjAiUiState(phase = DjAiPhase.GENERATING),
        )
        val afterResult = reduceVibeEvent(VibeCreateEvent.Generated(makeTestVibe()), generating)
        assertEquals(DjAiPhase.RESULT, afterResult.phase)

        val afterCompleted = reduceActivityEvent(
            AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"),
            afterResult,
        )
        val tool = afterCompleted.feed.single() as DjAiFeedItem.Tool
        assertEquals("Apply Vibe", tool.name)
        assertTrue(!tool.running)

        val afterReply = reduceActivityEvent(
            AgentActivityEvent.Assistant("Vibe ready: X"),
            afterCompleted,
        )
        assertEquals("Vibe ready: X", (afterReply.feed.last() as DjAiFeedItem.Reply).text)
    }

    // ─────────────────────────────────────────────────
    // Unified feed — Reasoning grouping and headline splits
    // ─────────────────────────────────────────────────

    @Test
    fun feed_reasoningCreatesNullHeadlineThinkingTail() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("just prose"), s0)
        val item = s1.feed.single() as DjAiFeedItem.Thinking
        assertNull(item.headline)
        assertEquals("just prose", item.text)
    }

    @Test
    fun feed_reasoningChunksAppendToTailThinking() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("part one "), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.Reasoning("part two"), s1)
        val item = s2.feed.single() as DjAiFeedItem.Thinking
        assertEquals("part one part two", item.text)
    }

    @Test
    fun feed_headlineWithBlankPreambleRelabelsInPlace() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(
            AgentActivityEvent.Reasoning("**Considering the Request**\nI'm thinking…"),
            s0,
        )
        val item = s1.feed.single() as DjAiFeedItem.Thinking
        assertEquals("Considering the Request", item.headline)
        assertEquals("\nI'm thinking…", item.text)
    }

    @Test
    fun feed_headlineSplitAcrossChunksLabelsExistingItem() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("**Defining the"), s0)
        val id0 = s1.feed.single().id
        val s2 = reduceActivityEvent(AgentActivityEvent.Reasoning(" Key** and now the body"), s1)
        val item = s2.feed.single() as DjAiFeedItem.Thinking
        assertEquals("Defining the Key", item.headline)
        assertEquals(" and now the body", item.text)
        assertEquals(id0, item.id)
    }

    @Test
    fun feed_nonBlankPreambleSplitsIntoSeparateItems() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(
            AgentActivityEvent.Reasoning("warming up **Crafting the Bass** low end"),
            s0,
        )
        assertEquals(2, s1.feed.size)
        val preamble = s1.feed[0] as DjAiFeedItem.Thinking
        val labeled = s1.feed[1] as DjAiFeedItem.Thinking
        assertNull(preamble.headline)
        assertEquals("warming up ", preamble.text)
        assertEquals("Crafting the Bass", labeled.headline)
        assertEquals(" low end", labeled.text)
        assertTrue(preamble.id != labeled.id)
    }

    @Test
    fun feed_multipleHeadlinesInOneChunkSplitLeftToRight() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(
            AgentActivityEvent.Reasoning("**Tempo** at 120 **Percussion** ghost notes"),
            s0,
        )
        assertEquals(2, s1.feed.size)
        val first = s1.feed[0] as DjAiFeedItem.Thinking
        val second = s1.feed[1] as DjAiFeedItem.Thinking
        assertEquals("Tempo", first.headline)
        assertEquals(" at 120 ", first.text)
        assertEquals("Percussion", second.headline)
        assertEquals(" ghost notes", second.text)
    }

    @Test
    fun feed_toolEventEndsThinkingSegment() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("pondering"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_vibe_schema"), s1)
        val s3 = reduceActivityEvent(AgentActivityEvent.Reasoning("new segment"), s2)
        assertEquals(3, s3.feed.size)
        assertTrue(s3.feed[1] is DjAiFeedItem.Tool)
        val newSegment = s3.feed[2] as DjAiFeedItem.Thinking
        assertNull(newSegment.headline)
        assertEquals("new segment", newSegment.text)
    }

    @Test
    fun feed_blankReasoningChunkCreatesNoItem() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("  \n"), s0)
        assertEquals(emptyList<DjAiFeedItem>(), s1.feed)
    }

    @Test
    fun feed_degenerateHeadlineMarkerIsDroppedWithoutSplit() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("before ** : ** after"), s0)
        val item = s1.feed.single() as DjAiFeedItem.Thinking
        assertNull(item.headline)
        assertEquals("before  after", item.text)
    }

    @Test
    fun feed_blankChunkAppendsToOpenThinkingTailWithoutNewItem() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("pondering"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.Reasoning("  "), s1)
        val item = s2.feed.single() as DjAiFeedItem.Thinking
        assertEquals("pondering  ", item.text)
        assertEquals(s1.feed.single().id, item.id)
    }

    // ─────────────────────────────────────────────────
    // Unified feed — Tool and Reply rows
    // ─────────────────────────────────────────────────

    @Test
    fun feed_toolStartedAppendsRunningTool() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        val tool = s1.feed.single() as DjAiFeedItem.Tool
        assertEquals("Apply Vibe", tool.name)
        assertTrue(tool.running)
    }

    @Test
    fun feed_toolCompletedFlipsMatchingRunningToolInPlace() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        val id0 = s1.feed.single().id
        val s2 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s1)
        val tool = s2.feed.single() as DjAiFeedItem.Tool
        assertEquals(id0, tool.id)
        assertTrue(!tool.running)
    }

    @Test
    fun feed_toolCompletedWithoutStartAppendsCompletedRow() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_get_vibe"), s0)
        val tool = s1.feed.single() as DjAiFeedItem.Tool
        assertEquals("Current Vibe", tool.name)
        assertTrue(!tool.running)
    }

    @Test
    fun feed_repeatedToolCompletionFlipsLatestRun() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s1)
        val s3 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s2)
        val s4 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s3)
        assertEquals(2, s4.feed.size)
        assertTrue(s4.feed.all { !(it as DjAiFeedItem.Tool).running })
    }

    @Test
    fun feed_assistantAppendsReplyRow() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Assistant("Here is your vibe!"), s0)
        assertEquals("Here is your vibe!", (s1.feed.single() as DjAiFeedItem.Reply).text)
    }

    @Test
    fun feed_consecutiveAssistantReplacesTailReply() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Assistant("first"), s0)
        val id0 = s1.feed.single().id
        val s2 = reduceActivityEvent(AgentActivityEvent.Assistant("second"), s1)
        val reply = s2.feed.single() as DjAiFeedItem.Reply
        assertEquals("second", reply.text)
        assertEquals(id0, reply.id)
    }

    @Test
    fun feed_assistantAfterInterveningItemAppendsNewReply() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Assistant("first"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s1)
        val s3 = reduceActivityEvent(AgentActivityEvent.Assistant("second"), s2)
        assertEquals(3, s3.feed.size)
        assertEquals("second", (s3.feed[2] as DjAiFeedItem.Reply).text)
    }

    @Test
    fun feed_idsAreUniqueAcrossItems() {
        var s = DjAiUiState(phase = DjAiPhase.GENERATING)
        s = reduceActivityEvent(AgentActivityEvent.Reasoning("a **B** c"), s)
        s = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s)
        s = reduceActivityEvent(AgentActivityEvent.Assistant("done"), s)
        val ids = s.feed.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }

    // ─────────────────────────────────────────────────
    // clearRunOutput — shared submit()/reset() clear
    // ─────────────────────────────────────────────────

    @Test
    fun clearRunOutputEmptiesFeedAndResetsIds() {
        var s = DjAiUiState(phase = DjAiPhase.GENERATING)
        s = reduceActivityEvent(AgentActivityEvent.Reasoning("**Tempo** thinking"), s)
        s = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s)
        val cleared = clearRunOutput(s.copy(error = "boom"))
        assertEquals(emptyList<DjAiFeedItem>(), cleared.feed)
        assertEquals(0L, cleared.nextId)
        assertNull(cleared.error)
    }

    // ─────────────────────────────────────────────────
    // Agent errors surface instead of spinning forever
    // ─────────────────────────────────────────────────

    @Test
    fun errorEventEndsRunWithIdleAndErrorMessage() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.Error("400: max_tokens too small"), s1)
        assertEquals(DjAiPhase.IDLE, s2.phase)
        assertEquals("400: max_tokens too small", s2.error)
        // The feed keeps its failure context for the user to inspect.
        assertEquals(s1.feed, s2.feed)
    }

    @Test
    fun idleGuard_errorDroppedWhenIdle() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.Error("stale failure"), s0)
        assertEquals(s0, s1)
    }

    // ─────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────

    /** Build a minimal valid [Vibe] for test assertions. */
    private fun makeTestVibe(): Vibe {
        val engine = OrpheusEngine(engineId = OrpheusEngineId.VA)
        return Vibe(
            name = "Test Vibe",
            tracks = List(8) { TrackVoice(engineEdm = engine, engineSpace = engine) },
            bpm = 120f,
            rootNote = RootNote.A,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0f,
                noteRangeLow = 36,
                noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.SPARSE.density,
            ),
        )
    }
}
