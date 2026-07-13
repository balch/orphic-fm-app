package org.balch.orpheus.features.ai

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningStreamTest {

    @Test
    fun `chunker emits on sentence boundaries and flushes the remainder`() {
        val chunks = mutableListOf<String>()
        val chunker = ReasoningChunker { chunks.add(it) }
        // > 40 chars and ends in '.', so it flushes a sentence chunk:
        chunker.consume(StreamFrame.ReasoningDelta(text = "Ohio is in E minor pentatonic, about 104 bpm. "))
        // short, no boundary -> buffered, not yet emitted:
        chunker.consume(StreamFrame.ReasoningDelta(text = "Bass on WSH"))
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("E minor pentatonic"))
        // ReasoningComplete flushes the remainder:
        chunker.consume(StreamFrame.ReasoningComplete(id = null, content = emptyList()))
        assertEquals(2, chunks.size)
        assertEquals("Bass on WSH", chunks[1])
    }

    @Test
    fun `collapseToMessage taps reasoning from the stream`() = runTest {
        val reasoning = mutableListOf<String>()
        val frames = flowOf(
            StreamFrame.ReasoningDelta(text = "Thinking about the key and tempo for this vibe now. "),
            StreamFrame.TextComplete(text = "Created the vibe."),
        )
        // Return type is Message.Assistant (compile-guaranteed); toMessageResponse fidelity is Koog's concern.
        frames.collapseToMessage { reasoning.add(it) }
        assertTrue(reasoning.any { it.contains("key and tempo") }, "reasoning should be tapped")
    }

    @Test
    fun `collapseToMessage tolerates zero-arg tool calls (empty streamed args)`() = runTest {
        // Anthropic streams NO input_json deltas for tools with no parameters
        // (pulsar_get_vibe, pulsar_vibe_schema), so ToolCallComplete.content arrives "".
        // Koog's toMessageResponse would throw JsonDecodingException on it and kill the run.
        val frames = flowOf(
            StreamFrame.ToolCallComplete(id = "toolu_1", name = "pulsar_get_vibe", content = ""),
            StreamFrame.TextComplete(text = "done"),
        )
        val message = frames.collapseToMessage { }
        val call = message.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("pulsar_get_vibe", call.tool)
        assertEquals("{}", call.args.toString(), "empty streamed args must normalize to {}")
    }

    @Test
    fun `tapReasoning chunks a batched message's reasoning parts and returns it unchanged`() {
        val chunks = mutableListOf<String>()
        val message = ai.koog.prompt.message.Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning(
                    id = null,
                    content = listOf("Choosing a dusty blues key for this vibe. Then setting tempo."),
                ),
                MessagePart.Text("Done."),
            ),
            metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
        )
        val returned = message.tapReasoning { chunks.add(it) }
        assertEquals(message, returned)
        assertTrue(chunks.any { it.contains("dusty blues key") }, "reasoning should be tapped")
    }

    @Test
    fun `tapToolTurnNarration surfaces text parts of a tool-call turn, chained after tapReasoning`() {
        val chunks = mutableListOf<String>()
        val message = ai.koog.prompt.message.Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning(
                    id = null,
                    content = listOf("Considering the key and tempo for this vibe."),
                ),
                MessagePart.Text("Fetching a template."),
                MessagePart.Tool.Call(id = "toolu_1", tool = "pulsar_get_vibe", args = "{}"),
            ),
            metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
        )
        val returned = message.tapReasoning { chunks.add(it) }.tapToolTurnNarration { chunks.add(it) }
        assertTrue(returned === message, "message must be returned unchanged (same instance)")
        assertTrue(chunks.any { it.contains("key and tempo") }, "tapReasoning behavior must be unchanged when chained")
        assertTrue(chunks.any { it == "Fetching a template." }, "tool-turn narration text should be tapped")
    }

    @Test
    fun `tapToolTurnNarration emits nothing for a text-only turn with no tool calls`() {
        val chunks = mutableListOf<String>()
        val message = ai.koog.prompt.message.Message.Assistant(
            parts = listOf(MessagePart.Text("final reply")),
            metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
        )
        val returned = message.tapToolTurnNarration { chunks.add(it) }
        assertEquals(message, returned)
        assertTrue(returned === message, "message must be returned unchanged (same instance)")
        assertTrue(chunks.isEmpty(), "text-only turns must not be tapped here; that text is the final reply")
    }

    @Test
    fun `tapToolTurnNarration skips blank text parts on a tool-call turn`() {
        val chunks = mutableListOf<String>()
        val message = ai.koog.prompt.message.Message.Assistant(
            parts = listOf(
                MessagePart.Text("   \n  "),
                MessagePart.Tool.Call(id = "toolu_1", tool = "pulsar_get_vibe", args = "{}"),
            ),
            metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
        )
        val returned = message.tapToolTurnNarration { chunks.add(it) }
        assertEquals(message, returned)
        assertTrue(chunks.isEmpty(), "blank/whitespace text parts must not be emitted")
    }
}
