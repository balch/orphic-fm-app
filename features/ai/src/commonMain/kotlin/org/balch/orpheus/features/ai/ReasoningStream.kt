package org.balch.orpheus.features.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList

/**
 * Buffers ReasoningDelta tokens into readable sentence-sized chunks, calling [onChunk] at sentence
 * boundaries and on flush. Adapted verbatim from SynthAgentStructure.parseSynthActions. Pure + testable.
 */
internal class ReasoningChunker(private val onChunk: (String) -> Unit) {
    private val buffer = StringBuilder()

    fun consume(frame: StreamFrame) {
        when (frame) {
            is StreamFrame.ReasoningDelta -> {
                val text = frame.text ?: frame.summary ?: return
                buffer.append(text)
                val content = buffer.toString()
                val lastSentenceEnd = content.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                if (lastSentenceEnd > 0 && content.length > 40) {
                    val sentence = content.substring(0, lastSentenceEnd + 1).trim()
                    if (sentence.isNotEmpty()) onChunk(sentence)
                    buffer.clear()
                    buffer.append(content.substring(lastSentenceEnd + 1))
                }
            }
            is StreamFrame.ReasoningComplete -> flush()
            else -> {}
        }
    }

    fun flush() {
        val remaining = buffer.toString().trim()
        if (remaining.isNotEmpty()) {
            onChunk(remaining)
            buffer.clear()
        }
    }
}

/**
 * Collect a streaming LLM frame flow, tapping reasoning into [onReasoning] as sentence chunks, then
 * collapse the frames back into the SAME Message.Assistant the batched node produced — so the agent
 * graph's downstream tool/text edges are unaffected. (toMessageResponse surfaces only the final
 * consolidated ReasoningComplete, not the incremental deltas — so per-sentence streaming to the feed
 * must tap the deltas here, before the collapse.)
 */
internal suspend fun Flow<StreamFrame>.collapseToMessage(onReasoning: (String) -> Unit): Message.Assistant {
    val chunker = ReasoningChunker(onReasoning)
    val frames = onEach { chunker.consume(it) }.toList()
    chunker.flush()
    // Zero-arg tool calls (pulsar_get_vibe, pulsar_vibe_schema) stream no input_json
    // deltas on Anthropic, so ToolCallComplete.content arrives as "" — and Koog's
    // toMessageResponse() parses it with a raw Json.parseToJsonElement, which throws on
    // empty input and killed the whole run. Normalize to an explicit empty object.
    return frames
        .map { frame ->
            if (frame is StreamFrame.ToolCallComplete && frame.content.isBlank()) {
                frame.copy(content = "{}")
            } else {
                frame
            }
        }
        .toMessageResponse()
}

/**
 * Batched-path twin of [collapseToMessage]: taps reasoning from an already-complete
 * assistant message into [onReasoning] as sentence chunks, returning the message
 * unchanged. Used for providers that must run non-streaming (Anthropic on Koog 1.0.0 —
 * the streaming path drops thinking signatures and never appends the assistant turn to
 * history, both of which Anthropic's strict tool-loop replay rules reject).
 */
internal fun Message.Assistant.tapReasoning(onReasoning: (String) -> Unit): Message.Assistant {
    val chunker = ReasoningChunker(onReasoning)
    parts.filterIsInstance<MessagePart.Reasoning>().forEach { part ->
        part.content.forEach { chunker.consume(StreamFrame.ReasoningDelta(text = it)) }
        part.summary?.forEach { chunker.consume(StreamFrame.ReasoningDelta(text = null, summary = it)) }
    }
    chunker.flush()
    return this
}

/**
 * Taps the narration text of a tool-call turn (e.g. "Now I'll fetch a template...") into
 * [onReasoning] so it reaches the activity feed as a thinking bubble. Tool-call turns' text parts
 * otherwise have no path to the feed; text-only turns (no tool calls) are excluded here because
 * their text is the final chat reply, delivered separately via onAssistantMessage — tapping it here
 * would duplicate it in the feed.
 */
internal fun Message.Assistant.tapToolTurnNarration(onReasoning: (String) -> Unit): Message.Assistant {
    if (parts.none { it is MessagePart.Tool.Call }) return this
    parts.filterIsInstance<MessagePart.Text>().forEach { part ->
        val trimmed = part.text.trim()
        if (trimmed.isNotEmpty()) onReasoning(trimmed)
    }
    return this
}
