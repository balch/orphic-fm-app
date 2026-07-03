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
    // reduceActivityEvent — Reasoning path
    // ─────────────────────────────────────────────────

    @Test
    fun reasoningGoesToThinkingOnly() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("hm"), s0)
        assertEquals(listOf("hm"), s1.thinking)
        assertEquals(emptyList<String>(), s1.activity)
    }

    @Test
    fun reasoningAppendsToExistingThinking() {
        val s0 = DjAiUiState(
            phase = DjAiPhase.GENERATING,
            thinking = listOf("first thought"),
        )
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("second thought"), s0)
        assertEquals(listOf("first thought", "second thought"), s1.thinking)
        assertEquals(emptyList<String>(), s1.activity)
    }

    // ─────────────────────────────────────────────────
    // reduceActivityEvent — Tool / Assistant path
    // ─────────────────────────────────────────────────

    @Test
    fun toolStartedGoesToActivityOnly() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        assertEquals(emptyList<String>(), s1.thinking)
        assertEquals(1, s1.activity.size)
        assertTrue(s1.activity[0].isNotBlank())
    }

    @Test
    fun toolCompletedGoesToActivityOnly() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s0)
        assertEquals(emptyList<String>(), s1.thinking)
        assertEquals(1, s1.activity.size)
        assertTrue(s1.activity[0].isNotBlank())
    }

    @Test
    fun assistantGoesToReplyNotActivity() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Assistant("Here is your vibe!"), s0)
        // The main reply surfaces in the status card, never in the activity log or thinking.
        assertEquals("Here is your vibe!", s1.assistantReply)
        assertEquals(emptyList<String>(), s1.activity)
        assertEquals(emptyList<String>(), s1.thinking)
    }

    @Test
    fun assistantLatestReplyWins() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Assistant("first"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.Assistant("second"), s1)
        assertEquals("second", s2.assistantReply)
    }

    // ─────────────────────────────────────────────────
    // Thinking headlines mirrored into Activity
    // ─────────────────────────────────────────────────

    @Test
    fun extractHeadlinesPullsBoldSpans() {
        val headlines = extractHeadlines("**Defining the Key and Tempo**\nblah blah **Crafting the Bass** more")
        assertEquals(listOf("Defining the Key and Tempo", "Crafting the Bass"), headlines)
    }

    @Test
    fun extractHeadlinesIgnoresPlainText() {
        assertEquals(emptyList<String>(), extractHeadlines("no bold headers here, just prose"))
    }

    @Test
    fun reasoningMirrorsHeadlineIntoActivity() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("**Considering the Request**\nI'm thinking…"), s0)
        assertEquals(listOf("**Considering the Request**\nI'm thinking…"), s1.thinking)
        assertEquals(listOf("💭 Considering the Request"), s1.activity)
    }

    @Test
    fun reasoningHeadlineNotDuplicatedAcrossChunks() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        // Same headline present in two streamed chunks must appear once in activity.
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("**Tuning the Groove** part one"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.Reasoning("**Tuning the Groove** part two continues"), s1)
        assertEquals(1, s2.activity.count { it == "💭 Tuning the Groove" })
        // A new headline in a later chunk is added.
        val s3 = reduceActivityEvent(AgentActivityEvent.Reasoning("**Adding Percussion** now"), s2)
        assertEquals(listOf("💭 Tuning the Groove", "💭 Adding Percussion"), s3.activity)
    }

    @Test
    fun reasoningWithoutHeadlineLeavesActivityUnchanged() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING, activity = listOf("🔧 Apply Vibe…"))
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("just prose, no headers"), s0)
        assertEquals(listOf("🔧 Apply Vibe…"), s1.activity)
        assertEquals(listOf("just prose, no headers"), s1.thinking)
    }

    @Test
    fun toolCompletedReplacesItsRunningRowInPlace() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s1)
        assertEquals(1, s2.activity.size)
        assertTrue(s2.activity[0].startsWith("✓"))
    }

    @Test
    fun toolCompletedOnlyReplacesLatestRunOfRepeatedTool() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s1)
        val s3 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s2)
        assertEquals(2, s3.activity.size)
        assertTrue(s3.activity[0].startsWith("✓"))
        assertTrue(s3.activity[1].startsWith("🔧"))
        val s4 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), s3)
        assertEquals(2, s4.activity.size)
        assertTrue(s4.activity[1].startsWith("✓"))
    }

    @Test
    fun toolEventsDoNotClearActivity() {
        val s0 = DjAiUiState(
            phase = DjAiPhase.GENERATING,
            activity = listOf("previous step"),
        )
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_vibe_schema"), s0)
        assertEquals(2, s1.activity.size)
        assertEquals("previous step", s1.activity[0])
    }

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
        assertEquals(emptyList<String>(), s1.activity)
        assertEquals(emptyList<String>(), s1.thinking)
    }

    @Test
    fun idleGuard_toolCompletedDroppedWhenIdle() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_get_vibe"), s0)
        assertEquals(s0, s1)
        assertEquals(emptyList<String>(), s1.activity)
        assertEquals(emptyList<String>(), s1.thinking)
    }

    @Test
    fun idleGuard_reasoningDroppedWhenIdle() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("stale thought"), s0)
        assertEquals(s0, s1)
        assertEquals(emptyList<String>(), s1.thinking)
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
        val generating = DjAiUiState(
            phase = DjAiPhase.GENERATING,
            activity = listOf("🔧 Apply Vibe…"),
        )
        val afterResult = reduceVibeEvent(VibeCreateEvent.Generated(makeTestVibe()), generating)
        assertEquals(DjAiPhase.RESULT, afterResult.phase)

        val afterCompleted = reduceActivityEvent(
            AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"),
            afterResult,
        )
        assertEquals(listOf("✓ Apply Vibe"), afterCompleted.activity)

        val afterReply = reduceActivityEvent(
            AgentActivityEvent.Assistant("Vibe ready: X"),
            afterCompleted,
        )
        assertEquals("Vibe ready: X", afterReply.assistantReply)
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
