package org.balch.orpheus.djapp.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.djapp.ai.collab.CollabEvent
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.MixMove
import org.balch.orpheus.djapp.ai.ideas.IdeasGenerator
import org.balch.orpheus.djapp.ai.ideas.IdeasInput
import org.balch.orpheus.djapp.ai.ideas.IdeasScheduler
import org.balch.orpheus.djapp.ai.mcp.McpEndpoint
import org.balch.orpheus.djapp.ai.mcp.McpEndpointState
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.PromptIntent
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.EmptyCoroutineContext
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
@OptIn(ExperimentalCoroutinesApi::class)
class DjAiViewModelTest {

    // ─────────────────────────────────────────────────
    // reduceActivityEvent — ToolStarted phase transitions
    // ─────────────────────────────────────────────────

    @Test
    fun vibeToolStartedWhileIdleIsAStray() {
        val s0 = DjAiUiState(phase = DjAiPhase.IDLE)
        assertEquals(s0, reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), s0))
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
        val s0 = DjAiUiState(phase = DjAiPhase.RESULT, error = "old error")
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
    fun vibeFailedKeepsTheTurnAndShowsError() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val s1 = reduceVibeEvent(VibeCreateEvent.Failed("timeout"), s0)
        assertEquals(DjAiPhase.GENERATING, s1.phase)
        assertEquals("timeout", s1.error)
    }

    @Test
    fun mcpApplyWhileIdleLeavesTheChatAlone() {
        val rig = ChatRig()
        assertTrue(!rig.chat.onVibeEvent(VibeCreateEvent.Generating))
        assertTrue(!rig.chat.onVibeEvent(VibeCreateEvent.Failed("bad json")))
        assertEquals(DjAiUiState(), rig.state.value)

        rig.chat.onVibeEvent(VibeCreateEvent.Generating)
        assertTrue(rig.chat.onVibeEvent(VibeCreateEvent.Generated(makeTestVibe(), outsideTurn = true)), "an MCP apply still plays")
        assertEquals(DjAiPhase.IDLE, rig.state.value.phase)
        assertTrue(!rig.chat.onVibeEvent(VibeCreateEvent.Generated(makeTestVibe())), "a stopped turn's late apply stays silent")
    }

    @Test
    fun inTurnApplyPlays() {
        val rig = ChatRig()
        rig.chat.send("dub", null)
        rig.chat.onVibeEvent(VibeCreateEvent.Generating)
        assertTrue(rig.chat.onVibeEvent(VibeCreateEvent.Generated(makeTestVibe())))
        assertEquals(DjAiPhase.RESULT, rig.state.value.phase)
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
    // A send seeds a Request row and keeps the feed
    // ─────────────────────────────────────────────────

    @Test
    fun blankDraftRunKeepsLastPromptBlankAndModelSwitchLeavesDraftBlank() {
        val rig = ChatRig(DjAiUiState(reference = SongPasteRef("paste-1", 10, null)))
        rig.chat.send("", rig.state.value.reference, clearDraft = true)
        val s1 = rig.state.value
        assertEquals("", s1.lastPrompt)
        assertEquals(listOf<DjAiFeedItem>(DjAiFeedItem.Request(0, "♪ song reference")), s1.feed)
        assertEquals("", applyModelSwitch(s1).promptDraft)
    }

    @Test
    fun sendSeedsRequestRowAndRemembersLastPrompt() {
        val rig = ChatRig(DjAiUiState(promptDraft = "dark techno", error = "stale error"))
        rig.chat.send("dark techno", null, clearDraft = true)
        val s1 = rig.state.value
        assertEquals(DjAiPhase.GENERATING, s1.phase)
        assertEquals(listOf<DjAiFeedItem>(DjAiFeedItem.Request(id = 0, text = "dark techno")), s1.feed)
        assertEquals(1L, s1.nextId)
        assertEquals("dark techno", s1.lastPrompt)
        assertEquals("", s1.promptDraft)
        assertNull(s1.error)
    }

    @Test
    fun feedKeepsEarlierTurns() {
        val rig = ChatRig()
        rig.chat.send("dark techno", null)
        rig.reply("Here it is.")
        assertEquals(DjAiPhase.IDLE, rig.state.value.phase)
        rig.chat.send("slower", null)
        assertEquals(
            listOf("dark techno", "Here it is.", "slower"),
            rig.state.value.feed.map { (it as? DjAiFeedItem.Request)?.text ?: (it as DjAiFeedItem.Reply).text },
        )
        assertEquals(DjAiPhase.GENERATING, rig.state.value.phase)
    }

    @Test
    fun promptCarriesTurnContextButDisplayIsDraft() {
        val rig = ChatRig()
        rig.collab.setMixerVisible(true)
        rig.collab.togglePin(3)
        rig.collab.record(EditOrigin.USER, MixControl.Level(5), "-2.0 dB", "-5.0 dB")
        rig.chat.send("more air", null)
        val sent = rig.sent.single()
        assertEquals("more air", sent.displayText)
        assertEquals(
            "[Mixer: open. Pinned: BASS]\n[User moves since your last turn, oldest first]\n- PAD level: -2.0 dB -> -5.0 dB\n\nmore air",
            sent.prompt,
        )
        assertTrue(rig.collab.turnActive.value)
        rig.reply("Done.")
        assertTrue(!rig.collab.turnActive.value)
    }

    @Test
    fun noVibePrefixOnPlainMessage() {
        val rig = ChatRig()
        rig.chat.send("make it slower", null)
        val prompt = rig.sent.single().prompt
        assertTrue(prompt.endsWith("\n\nmake it slower"), prompt)
        assertTrue("Make a Pulsar vibe" !in prompt, prompt)
    }

    @Test
    fun dragShowsOneFeedRow() {
        val rig = ChatRig()
        listOf("-2.0 dB" to "-3.0 dB", "-3.0 dB" to "-4.0 dB", "-4.0 dB" to "-5.0 dB").forEachIndexed { i, (b, a) ->
            rig.chat.onMove(MixMove(EditOrigin.USER, MixControl.Level(3), b, a, atMs = i.toLong()))
        }
        assertEquals(
            listOf<DjAiFeedItem>(DjAiFeedItem.Move(0, EditOrigin.USER, "BASS level", "-2.0 dB", "-5.0 dB")),
            rig.state.value.feed,
        )
    }

    @Test
    fun dragBackToStartLeavesNoRow() {
        val rig = ChatRig()
        rig.chat.onMove(MixMove(EditOrigin.USER, MixControl.Level(3), "-2.0 dB", "-4.0 dB", atMs = 0))
        rig.chat.onMove(MixMove(EditOrigin.USER, MixControl.Level(3), "-4.0 dB", "-2.0 dB", atMs = 1))
        assertEquals(emptyList(), rig.state.value.feed)
    }

    @Test
    fun agentMoveShowsOnlyDuringATurn() {
        val rig = ChatRig()
        val move = MixMove(EditOrigin.AGENT, MixControl.Pan(1), "C", "L20", atMs = 0)
        rig.chat.onMove(move)
        assertEquals(emptyList(), rig.state.value.feed)
        rig.chat.send("pan the perc", null)
        rig.chat.onMove(move)
        assertEquals(DjAiFeedItem.Move(1, EditOrigin.AGENT, "PERC pan", "C", "L20"), rig.state.value.feed.last())
    }

    @Test
    fun userMovesFeedIdeasOnlyWhileTheSheetIsOpen() = runTest {
        val calls = mutableListOf<IdeasInput>()
        val ideas = IdeasScheduler(backgroundScope, IdeasGenerator { calls += it; emptyList() }, clock = { testScheduler.currentTime })
        val rig = ChatRig()
        val input = IdeasInput(mixerOpen = false, vibeSummary = "", mixSnapshot = "", recentMoves = emptyList(), recentChat = emptyList())
        fun move(open: Boolean, origin: EditOrigin = EditOrigin.USER) =
            onMixMove(MixMove(origin, MixControl.MacroKnob(Macro.ENERGY), "0.50", "0.70", atMs = 0), open, rig.chat, ideas) { input }

        // A knob twisted on the Pulsar panel with the AI sheet closed: no call.
        move(open = false)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, calls.size)
        assertEquals(1, rig.state.value.feed.size, "the move still joins the chat")

        // The sheet open on the chat alone, then on the Mixer: each pause makes one call.
        move(open = true)
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(1, calls.size)
        rig.collab.setMixerVisible(true)
        move(open = true)
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(2, calls.size)

        // The agent's own moves never start a pause.
        move(open = true, origin = EditOrigin.AGENT)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, calls.size)
    }

    @Test
    fun queuedMessagesSendOnceAfterTurn() {
        val rig = ChatRig()
        rig.chat.send("a", null)
        rig.chat.send("b", null)
        rig.chat.send("c", null)
        assertEquals(1, rig.sent.size)
        assertEquals(listOf("b", "c"), rig.state.value.queued)

        rig.reply("ok")
        assertEquals(2, rig.sent.size)
        assertEquals("b\n\nc", rig.sent[1].displayText)
        assertTrue(rig.sent[1].prompt.endsWith("\n\nb\n\nc"), rig.sent[1].prompt)
        assertEquals(emptyList(), rig.state.value.queued)
        assertEquals(DjAiPhase.GENERATING, rig.state.value.phase)
        assertEquals(DjAiFeedItem.Request(2, "b\n\nc"), rig.state.value.feed.last())

        rig.reply("ok again")
        assertEquals(2, rig.sent.size)
    }

    @Test
    fun turnHooksBracketEachTurn() {
        val rig = ChatRig()
        rig.chat.switchModel("Idle Switch")
        rig.chat.newConversation()
        assertEquals(emptyList(), rig.turnHooks, "nothing was running")

        rig.chat.send("a", null)
        rig.chat.send("b", null)
        rig.reply("ok")
        assertEquals(listOf("start", "start"), rig.turnHooks, "a queued turn follows with no end between")
        rig.reply("ok again")
        assertEquals(listOf("start", "start", "end"), rig.turnHooks)

        rig.turnHooks.clear()
        rig.chat.send("c", null)
        rig.chat.cancel()
        rig.chat.send("d", null)
        rig.chat.onActivity(AgentActivityEvent.Error("429"))
        rig.chat.send("e", null)
        rig.chat.switchModel("Other")
        rig.chat.send("f", null)
        rig.chat.newConversation()
        assertEquals(List(4) { listOf("start", "end") }.flatten(), rig.turnHooks)
    }

    @Test
    fun cancelKeepsConversationViaRecap() {
        val rig = ChatRig()
        rig.chat.send("first", null)
        rig.reply("one " + "x".repeat(400))
        rig.chat.send("second", null)
        rig.chat.cancel()

        assertEquals(1, rig.restarts)
        assertEquals(DjAiPhase.IDLE, rig.state.value.phase)
        assertEquals("Stopped.", (rig.state.value.feed.last() as DjAiFeedItem.Notice).text)
        assertEquals(4, rig.state.value.feed.size)
        assertTrue(!rig.collab.turnActive.value)

        rig.chat.send("third", null)
        val reply = "Agent: " + ("one " + "x".repeat(400)).take(300)
        assertEquals(
            "[Earlier in this conversation]\nYou: first\n$reply\nYou: second\n\n[Mixer: closed. Pinned: none]\n\nthird",
            rig.sent.last().prompt,
        )
        rig.reply("three")
        rig.chat.send("fourth", null)
        assertTrue(!rig.sent.last().prompt.startsWith("[Earlier"), "the recap goes with one prompt only")
    }

    @Test
    fun errorReturnsQueuedToInputAndRecaps() {
        val rig = ChatRig()
        rig.chat.send("first", null)
        rig.chat.send("second", null)
        rig.chat.onActivity(AgentActivityEvent.Error("429"))
        assertEquals(1, rig.sent.size, "nothing goes to a run that just failed")
        assertEquals("429", rig.state.value.error)
        assertEquals("second", rig.state.value.promptDraft)
        assertTrue(!rig.collab.turnActive.value)

        rig.chat.send("again", null)
        assertTrue(rig.sent.last().prompt.startsWith("[Earlier in this conversation]\nYou: first\n\n"), rig.sent.last().prompt)
    }

    @Test
    fun cancelWithQueuedThenSendRecapsAndSendsTheQueuedTextOnce() {
        val rig = ChatRig()
        rig.chat.send("first", null)
        rig.chat.send("second", null)
        rig.chat.cancel()
        assertEquals("second", rig.state.value.promptDraft)

        rig.chat.send(rig.state.value.promptDraft, null, clearDraft = true)
        assertEquals(2, rig.sent.size)
        val prompt = rig.sent.last().prompt
        assertTrue(prompt.startsWith("[Earlier in this conversation]\nYou: first\n\n"), prompt)
        assertEquals(1, Regex("second").findAll(prompt).count(), prompt)
        assertTrue(prompt.endsWith("\n\nsecond"), prompt)
        assertEquals(emptyList(), rig.state.value.queued)
    }

    @Test
    fun cancelDropsButtonMessagesFromTheQueue() {
        val rig = ChatRig()
        rig.chat.send("a new song", null)
        rig.chat.onCollabEvent(CollabEvent.WipeConfirmRequested(2))
        rig.chat.onAction(rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single().id, accept = true)
        rig.chat.send("and darker", null)
        rig.chat.cancel()
        assertEquals("and darker", rig.state.value.promptDraft)
    }

    @Test
    fun referenceInstructionGoesOnce() {
        val ref = SongPasteRef("paste-7", 12, null)
        val rig = ChatRig(DjAiUiState(reference = ref))
        rig.chat.send("like this", ref)
        assertTrue("pasteId=\"paste-7\"" in rig.sent.last().prompt)
        rig.reply("Built it.")
        rig.chat.send("slower", ref)
        assertTrue("pasteId" !in rig.sent.last().prompt, "the agent already has the reference")
        rig.reply("Slower.")
        rig.chat.send("", ref)
        assertEquals(2, rig.sent.size, "a blank send with an announced reference has nothing to say")

        rig.chat.send("again", ref)
        rig.chat.cancel()
        rig.chat.send("once more", ref)
        assertTrue("pasteId=\"paste-7\"" in rig.sent.last().prompt, "a restart loses the history, so it is told again")
    }

    @Test
    fun toolRowsSummarizeTheirCalls() {
        val s0 = DjAiUiState(phase = DjAiPhase.GENERATING)
        val mix = reduceActivityEvent(
            AgentActivityEvent.ToolStarted("mix_set", """{"field":"level_db","value":-3.0,"track":3}"""),
            s0,
        )
        assertEquals("mix_set BASS −3.0 dB", (mix.feed.last() as DjAiFeedItem.Tool).summary)

        val vibeArgs = buildJsonObject { put("vibeJson", """{"name":"Midnight Tape","bpm":92}""") }.toString()
        val applied = reduceActivityEvent(AgentActivityEvent.ToolStarted("pulsar_apply_vibe", vibeArgs), mix)
        val done = reduceActivityEvent(AgentActivityEvent.ToolCompleted("pulsar_apply_vibe"), applied)
        val row = done.feed.last() as DjAiFeedItem.Tool
        assertEquals("pulsar_apply_vibe \"Midnight Tape\"", row.summary, "the summary survives the call finishing")
        assertTrue(!row.running)

        // Nothing telling to show, or no arguments: the row keeps its plain label.
        assertNull((reduceActivityEvent(AgentActivityEvent.ToolStarted("mix_read", "{}"), s0).feed.last() as DjAiFeedItem.Tool).summary)
        assertNull((reduceActivityEvent(AgentActivityEvent.ToolStarted("mix_set"), s0).feed.last() as DjAiFeedItem.Tool).summary)
    }

    @Test
    fun toolSummaryShapes() {
        assertEquals("mix_set PAD pan L40 · section", toolSummary("mix_set", """{"field":"pan","value":-0.4,"track":5,"scope":"section"}"""))
        assertEquals("mix_set energy 0.60", toolSummary("mix_set", """{"field":"macro","value":0.6,"macro":"energy"}"""))
        assertEquals("mix_set BASS +2.0 dB", toolSummary("mix_set", """{"field":"level_db","value":2,"track":3}"""))
        assertEquals("section_jump drop", toolSummary("section_jump", """{"name":"drop"}"""))
        assertEquals("mix_mute BASS", toolSummary("mix_mute", """{"track":3}"""))
        assertNull(toolSummary("mix_set", "not json"))
        // A big vibe's arguments arrive cut at 4 KB; the name still shows.
        val cut = buildJsonObject { put("vibeJson", """{"name":"Midnight Tape","tracks":[""" + "x".repeat(5_000)) }.toString().take(4_096)
        assertEquals("pulsar_apply_vibe \"Midnight Tape\"", toolSummary("pulsar_apply_vibe", cut))
    }

    @Test
    fun sendIsLiveOnlyWhenItWouldGo() {
        val ref = SongPasteRef("paste-7", 12, null)
        assertTrue(!DjAiUiState(isKeySet = true).canSend(), "a blank send with nothing attached")
        assertTrue(DjAiUiState(isKeySet = true, promptDraft = "louder").canSend())
        assertTrue(!DjAiUiState(isKeySet = false, promptDraft = "louder").canSend(), "no key")
        assertTrue(DjAiUiState(isKeySet = true, reference = ref).canSend(), "a reference the agent has not seen")
        assertTrue(!DjAiUiState(isKeySet = true, reference = ref, referenceSentId = "paste-7").canSend())
        assertTrue(DjAiUiState(isKeySet = true, parsing = 3).canSend(), "a paste still parsing")
    }

    @Test
    fun recapKeepsTheLastTwelveMessages() {
        val feed = (0 until 20).map { DjAiFeedItem.Request(it.toLong(), "m$it") }
        val lines = conversationRecap(feed).lines()
        assertEquals("[Earlier in this conversation]", lines.first())
        assertEquals((8 until 20).map { "You: m$it" }, lines.drop(1))
    }

    @Test
    fun modelSwitchAddsDivider() {
        val rig = ChatRig()
        rig.chat.send("a", null)
        rig.chat.cancel()
        rig.chat.switchModel("Claude Opus")
        assertEquals(2, rig.restarts)
        assertEquals("New conversation with Claude Opus", (rig.state.value.feed.last() as DjAiFeedItem.Divider).text)
        rig.chat.send("b", null)
        assertTrue(!rig.sent.last().prompt.startsWith("[Earlier"), "a new model starts without a recap")
    }

    @Test
    fun newConversationClearsTheFeed() {
        val rig = ChatRig()
        rig.chat.send("a", null)
        rig.chat.send("b", null)
        rig.chat.newConversation()
        assertEquals(1, rig.restarts)
        assertEquals(DjAiUiState(), rig.state.value)
        assertTrue(!rig.collab.turnActive.value)
    }

    @Test
    fun oneOpenMixerCardPerTurn() {
        val rig = ChatRig()
        rig.chat.send("louder bass", null)
        repeat(2) { rig.chat.onCollabEvent(CollabEvent.OpenMixerRequested("closed")) }
        val card = rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single()
        assertEquals(ActionKind.OPEN_MIXER, card.kind)
        assertEquals("Open the Mixer so I can make that change.", card.text)

        rig.reply("Open it for me.")
        rig.chat.send("go on", null)
        rig.chat.onCollabEvent(CollabEvent.OpenMixerRequested("closed"))
        assertEquals(2, rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().size)
    }

    @Test
    fun openMixerAcceptSwitchesModeAndSends() = runTest {
        val rig = ChatRig(scope = this)
        rig.chat.send("louder bass", null)
        rig.chat.onCollabEvent(CollabEvent.OpenMixerRequested("closed"))
        rig.reply("Open it for me.")
        val card = rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single()

        rig.chat.onAction(card.id, accept = true)
        assertEquals(AiSheetMode.MIXER, rig.state.value.sheetMode)
        assertEquals(true, rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single().answer)
        runCurrent()
        assertEquals(1, rig.sent.size, "waits for the Mixer to show")

        // The sheet shows the Mixer, as the UI does once the mode switches.
        rig.collab.setMixerVisible(true)
        runCurrent()
        assertEquals("The Mixer is open.", rig.sent.last().displayText)
        assertTrue(rig.sent.last().prompt.startsWith("[Mixer: open."), rig.sent.last().prompt)
        assertTrue(rig.sent.last().prompt.endsWith("\n\nThe Mixer is open."))

        rig.chat.onAction(card.id, accept = true)
        runCurrent()
        assertEquals(2, rig.sent.size, "a resolved card sends nothing")
    }

    @Test
    fun openMixerAcceptSaysClosedWhenTheMixerNeverShows() = runTest {
        val rig = ChatRig(scope = this)
        rig.chat.send("louder bass", null)
        rig.chat.onCollabEvent(CollabEvent.OpenMixerRequested("closed"))
        rig.reply("Open it for me.")
        rig.chat.onAction(rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single().id, accept = true)
        advanceTimeBy(MIXER_WAIT_MS - 1)
        assertEquals(1, rig.sent.size, "still waiting for the Mixer")
        advanceTimeBy(2)
        // The sheet closed before the Mixer showed: the message agrees with the turn's context.
        assertEquals(2, rig.sent.size)
        assertEquals("The Mixer is still closed.", rig.sent.last().displayText)
        assertTrue(rig.sent.last().prompt.startsWith("[Mixer: closed."))
        assertTrue(rig.sent.last().prompt.endsWith("\n\nThe Mixer is still closed."))
    }

    @Test
    fun wipeAcceptConfirmsThenSends() {
        val rig = ChatRig()
        rig.chat.send("a new song", null)
        rig.chat.onCollabEvent(CollabEvent.WipeConfirmRequested(3))
        rig.reply("This clears your mix. Go ahead?")
        val card = rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single()
        assertEquals(ActionKind.CONFIRM_WIPE, card.kind)
        assertEquals("This clears your mix: 3 changes.", card.text)

        rig.chat.onAction(card.id, accept = true)
        assertEquals("Yes, clear my mix and apply.", rig.sent.last().displayText)
        // Confirmed after beginTurn, which clears any confirmation: the agent's turn can spend it.
        assertTrue(rig.collab.consumeWipeConfirmation())
    }

    @Test
    fun wipeAcceptedMidTurnConfirmsTheQueuedTurn() {
        val rig = ChatRig()
        rig.chat.send("a new song", null)
        rig.chat.onCollabEvent(CollabEvent.WipeConfirmRequested(1))
        val card = rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single()
        assertEquals("This clears your mix: 1 change.", card.text)
        rig.chat.onAction(card.id, accept = true)
        assertEquals(listOf("Yes, clear my mix and apply."), rig.state.value.queued)
        assertTrue(!rig.collab.consumeWipeConfirmation(), "the running turn is not authorised")

        rig.reply("Want me to clear it?")
        assertEquals("Yes, clear my mix and apply.", rig.sent.last().displayText)
        assertTrue(rig.collab.consumeWipeConfirmation())
    }

    @Test
    fun wipeDeclineKeepsTheMix() {
        val rig = ChatRig()
        rig.chat.send("a new song", null)
        rig.chat.onCollabEvent(CollabEvent.WipeConfirmRequested(3))
        rig.reply("Go ahead?")
        val card = rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single()
        rig.chat.onAction(card.id, accept = false)
        assertEquals("Keep my mix; don't re-apply.", rig.sent.last().displayText)
        assertTrue(!rig.collab.consumeWipeConfirmation())
    }

    @Test
    fun skipNoticeNamesUserValue() {
        val rig = ChatRig()
        rig.chat.send("tame the bass", null)
        rig.chat.onCollabEvent(CollabEvent.AgentSkipped(MixControl.Level(3), "-4.0 dB"))
        assertEquals(
            "You moved BASS level while I was working, so I left it at your -4.0 dB.",
            (rig.state.value.feed.last() as DjAiFeedItem.Notice).text,
        )
    }

    // ─────────────────────────────────────────────────
    // applyModelSwitch — unstick + prefill
    // ─────────────────────────────────────────────────

    @Test
    fun modelSwitchDuringGenerationReturnsToIdleKeepingFeedAndError() {
        val feed = listOf<DjAiFeedItem>(
            DjAiFeedItem.Request(id = 0, text = "lofi beats"),
            DjAiFeedItem.Tool(id = 1, name = "Apply Vibe", running = true),
        )
        val s0 = DjAiUiState(
            phase = DjAiPhase.GENERATING,
            feed = feed,
            nextId = 2,
            error = "api blew up",
            lastPrompt = "lofi beats",
        )
        val s1 = applyModelSwitch(s0)
        assertEquals(DjAiPhase.IDLE, s1.phase)
        assertEquals(feed, s1.feed)
        assertEquals("api blew up", s1.error)
    }

    @Test
    fun modelSwitchWithBlankDraftPrefillsLastPrompt() {
        val s0 = DjAiUiState(promptDraft = "  ", lastPrompt = "lofi beats")
        val s1 = applyModelSwitch(s0)
        assertEquals("lofi beats", s1.promptDraft)
    }

    @Test
    fun modelSwitchWithTypedDraftKeepsIt() {
        val s0 = DjAiUiState(promptDraft = "new idea", lastPrompt = "lofi beats")
        val s1 = applyModelSwitch(s0)
        assertEquals("new idea", s1.promptDraft)
    }

    // ─────────────────────────────────────────────────
    // Request head row is inert to streaming reducers
    // ─────────────────────────────────────────────────

    @Test
    fun reasoningAndReplyAppendAfterRequestHeadWithoutMerging() {
        val s0 = DjAiUiState(
            phase = DjAiPhase.GENERATING,
            feed = listOf(DjAiFeedItem.Request(id = 0, text = "lofi beats")),
            nextId = 1,
        )
        val s1 = reduceActivityEvent(AgentActivityEvent.Reasoning("Picking a key. "), s0)
        val s2 = reduceActivityEvent(AgentActivityEvent.Assistant("Here you go."), s1)
        assertEquals(DjAiFeedItem.Request(id = 0, text = "lofi beats"), s2.feed[0])
        assertTrue(s2.feed[1] is DjAiFeedItem.Thinking)
        assertTrue(s2.feed[2] is DjAiFeedItem.Reply)
    }

    // ─────────────────────────────────────────────────
    // MCP endpoint
    // ─────────────────────────────────────────────────

    @Test
    fun stateCarriesTheMcpEndpoint() {
        // The VM's injected graph is too deep to build here, so its collector is the pure reducer.
        val published = McpEndpointState()
        val before = withMcpEndpoint(DjAiUiState(), published.endpoint.value)
        assertNull(before.mcpEndpoint)

        val endpoint = McpEndpoint(url = "http://127.0.0.1:47801/mcp", token = "t0k3n", port = 47801)
        published.publish(endpoint)
        assertEquals(endpoint, withMcpEndpoint(before, published.endpoint.value).mcpEndpoint)

        published.publish(null)
        assertNull(withMcpEndpoint(before, published.endpoint.value).mcpEndpoint)
    }

    // ─────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────

    @Test
    fun castModeDefaultsToBedFixedAndSelectionSticksInState() {
        assertEquals(CastMode.BED_FIXED, DjAiUiState().castMode)
        assertEquals(CastMode.ALL_ROLLED, withCastMode(DjAiUiState(), CastMode.ALL_ROLLED).castMode)
    }

    @Test
    fun savedCastModeLoadsOnlyOverTheDefault() {
        assertEquals(CastMode.ALL_ROLLED, withSavedCastMode(DjAiUiState(), CastMode.ALL_ROLLED).castMode)
        // The user tapped "everything rolled" before the saved BED_FIXED arrived: the tap stays.
        val tapped = withCastMode(DjAiUiState(), CastMode.ALL_ROLLED)
        assertEquals(CastMode.ALL_ROLLED, withSavedCastMode(tapped, CastMode.BED_FIXED).castMode)
    }

    @Test
    fun savedCastModeParsesWithFallback() {
        assertEquals(CastMode.ALL_ROLLED, castModeFromPref("ALL_ROLLED"))
        assertEquals(CastMode.BED_FIXED, castModeFromPref(null))
        assertEquals(CastMode.BED_FIXED, castModeFromPref("junk"))
    }

    @Test
    fun sheetModePersists() = runTest {
        val prefs = MemoryPrefs()
        val state = MutableStateFlow(DjAiUiState())
        SheetModeMemory(state, prefs, backgroundScope, EmptyCoroutineContext)
        runCurrent()
        assertEquals(AiSheetMode.CHAT, state.value.sheetMode, "never chosen opens the chat")

        state.update { it.copy(sheetMode = AiSheetMode.MIXER) }
        runCurrent()
        assertEquals("MIXER", prefs.saved.aiSheetMode)

        // The next launch opens the Mixer.
        val relaunched = MutableStateFlow(DjAiUiState())
        SheetModeMemory(relaunched, prefs, backgroundScope, EmptyCoroutineContext)
        runCurrent()
        assertEquals(AiSheetMode.MIXER, relaunched.value.sheetMode)

        relaunched.update { it.copy(sheetMode = AiSheetMode.CHAT) }
        runCurrent()
        assertEquals("CHAT", prefs.saved.aiSheetMode)
        assertEquals(AiSheetMode.CHAT, sheetModeFromPref("junk"))
    }

    @Test
    fun acceptedOpenMixerIsTheRememberedMode() = runTest {
        val prefs = MemoryPrefs()
        val rig = ChatRig(scope = this)
        SheetModeMemory(rig.state, prefs, backgroundScope, EmptyCoroutineContext)
        runCurrent()
        rig.chat.send("louder bass", null)
        rig.chat.onCollabEvent(CollabEvent.OpenMixerRequested("closed"))
        rig.chat.onAction(rig.state.value.feed.filterIsInstance<DjAiFeedItem.Action>().single().id, accept = true)
        runCurrent()
        assertEquals("MIXER", prefs.saved.aiSheetMode)
        advanceTimeBy(MIXER_WAIT_MS + 1)
    }

    private class MemoryPrefs(var saved: AppPreferences = AppPreferences()) : BaseAppPreferencesRepository() {
        override suspend fun load() = saved
        override suspend fun save(preferences: AppPreferences) { saved = preferences }
    }

    /** The conversation core with a recording agent, a real [MixCollab] and no coroutines. */
    private class ChatRig(initial: DjAiUiState = DjAiUiState(), scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) {
        val state = MutableStateFlow(initial)
        val collab = MixCollab()
        val sent = mutableListOf<PromptIntent>()
        var restarts = 0
        val turnHooks = mutableListOf<String>()
        val chat = DjAiChat(
            state,
            collab,
            scope,
            sendPrompt = { sent += it },
            restartAgent = { restarts++ },
            onTurnStarted = { turnHooks += "start" },
            onTurnEnded = { turnHooks += "end" },
        )

        fun reply(text: String) = chat.onActivity(AgentActivityEvent.Assistant(text))
    }

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
