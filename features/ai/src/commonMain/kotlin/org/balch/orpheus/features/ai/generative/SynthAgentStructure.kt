package org.balch.orpheus.features.ai.generative

import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.markdown.MarkdownStructureDefinition
import ai.koog.prompt.structure.markdown.markdownStreamingParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/**
 * Represents an action parsed from the LLM stream.
 */
data class AgentAction(
    val type: ActionType,
    val startHeader: String = "",
    val details: List<String> = emptyList()
)

enum class ActionType {
    CONTROL, REPL, STATUS, REASONING, UNKNOWN
}

/**
 * Defines the structure for the agent's output.
 */
fun synthActionDefinition(): MarkdownStructureDefinition {
    return MarkdownStructureDefinition("agentActions", schema = {
        markdown {
            header(1, "ACTION_TYPE")
            bulleted {
                item("Detail 1")
                item("Detail 2")
            }
        }
    }, examples = {
        markdown {
            header(1, "CONTROL")
            bulleted {
                item("drive")
                item("0.75")
            }
            header(1, "STATUS")
            bulleted {
                item("Adding grit to the texture")
            }
            header(1, "REPL")
            bulleted {
                item("d1 $ note \"c3\" # cut:1")
            }
        }
    })
}

/**
 * Parser for the synth actions stream.
 * 
 * Handles both Markdown structured output AND native ToolCalls.
 */
fun parseSynthActions(stream: Flow<StreamFrame>): Flow<AgentAction> {
    return channelFlow {
        // Channel to feed text chunks to the markdown parser
        val textChannel = Channel<String>(Channel.UNLIMITED)

        // Launch markdown parser on the text-only stream
        launch {
            markdownStreamingParser {
                var currentType = ActionType.UNKNOWN
                var currentHeader = ""
                val details = mutableListOf<String>()

                onHeader(1) { header ->
                    if (currentType != ActionType.UNKNOWN && details.isNotEmpty()) {
                        send(AgentAction(currentType, currentHeader, details.toList()))
                    }
                    currentHeader = header.uppercase().trim()
                    currentType = when {
                        currentHeader.contains("CONTROL") -> ActionType.CONTROL
                        currentHeader.contains("REPL") -> ActionType.REPL
                        currentHeader.contains("STATUS") -> ActionType.STATUS
                        else -> ActionType.UNKNOWN
                    }
                    details.clear()
                }

                onBullet { text ->
                    details.add(text)
                    if (currentType == ActionType.STATUS && details.size == 1) {
                         send(AgentAction(ActionType.STATUS, currentHeader, details.toList()))
                         details.clear()
                         currentType = ActionType.UNKNOWN
                    }
                    else if (currentType == ActionType.REPL && details.size == 1) {
                         send(AgentAction(ActionType.REPL, currentHeader, details.toList()))
                         details.clear()
                         currentType = ActionType.UNKNOWN
                    }
                    else if (currentType == ActionType.CONTROL && details.size == 2) {
                         send(AgentAction(ActionType.CONTROL, currentHeader, details.toList()))
                         details.clear()
                         currentType = ActionType.UNKNOWN
                    }
                }

                onFinishStream {
                    if (currentType != ActionType.UNKNOWN && details.isNotEmpty()) {
                        send(AgentAction(currentType, currentHeader, details.toList()))
                    }
                }
            }.parseStream(textChannel.receiveAsFlow())
        }

        // Accumulate reasoning deltas into sentence-sized chunks
        val reasoningBuffer = StringBuilder()

        // Process incoming stream: route text deltas to parser, handle tool calls directly
        stream.collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> textChannel.send(frame.text)
                is StreamFrame.ReasoningDelta -> {
                    val text = frame.text ?: frame.summary
                    if (text != null) {
                        reasoningBuffer.append(text)
                        // Emit when we hit sentence boundaries for readable chunks
                        val content = reasoningBuffer.toString()
                        val lastSentenceEnd = content.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                        if (lastSentenceEnd > 0 && content.length > 40) {
                            val sentence = content.substring(0, lastSentenceEnd + 1).trim()
                            if (sentence.isNotEmpty()) {
                                send(AgentAction(ActionType.REASONING, details = listOf(sentence)))
                            }
                            reasoningBuffer.clear()
                            reasoningBuffer.append(content.substring(lastSentenceEnd + 1))
                        }
                    }
                }
                is StreamFrame.ReasoningComplete -> {
                    // Flush any remaining reasoning buffer
                    val remaining = reasoningBuffer.toString().trim()
                    if (remaining.isNotEmpty()) {
                        send(AgentAction(ActionType.REASONING, details = listOf(remaining)))
                        reasoningBuffer.clear()
                    }
                }
                is StreamFrame.ToolCallComplete -> {
                    try {
                        val args = frame.contentJson
                        when (frame.name.lowercase()) {
                            "synth_control", "synthcontrol" -> {
                                val id = args["controlId"]?.jsonPrimitive?.content
                                val value = args["value"]?.jsonPrimitive?.float

                                if (id != null && value != null) {
                                    send(AgentAction(ActionType.CONTROL, details = listOf(id, value.toString())))
                                }
                            }
                            "repl_execute", "replexecute" -> {
                                val code = args["code"]?.jsonPrimitive?.content
                                if (code != null) {
                                    send(AgentAction(ActionType.REPL, details = listOf(code)))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore parse errors from tools
                    }
                }
                is StreamFrame.End -> {} // channel closed after collect completes
                else -> {}
            }
        }
        textChannel.close()
    }
}
