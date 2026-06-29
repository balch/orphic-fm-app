package org.balch.orpheus.features.ai

import ai.koog.prompt.message.Message
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
    return frames.toMessageResponse()
}
