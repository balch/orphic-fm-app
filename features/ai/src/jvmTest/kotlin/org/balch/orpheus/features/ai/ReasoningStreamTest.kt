package org.balch.orpheus.features.ai

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
}
