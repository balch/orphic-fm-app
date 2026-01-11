package org.balch.orpheus.features.ai.generative

import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.filterTextOnly
import ai.koog.prompt.structure.markdown.MarkdownStructureDefinition
import ai.koog.prompt.structure.markdown.markdownStreamingParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    CONTROL, REPL, STATUS, UNKNOWN
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
        val json = Json { ignoreUnknownKeys = true }
        // Channel to feed text parts to the markdown parser
        val textChannel = kotlinx.coroutines.channels.Channel<StreamFrame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        
        // Launch markdown parser
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
            }.parseStream(textChannel.receiveAsFlow().filterTextOnly())
        }

        // Process incoming stream
        stream.collect { frame ->
            when (frame) {
                is StreamFrame.Append -> textChannel.send(frame)
                is StreamFrame.ToolCall -> {
                    try {
                        when (frame.name) {
                            "synth_control" -> {
                                val args = json.decodeFromString<JsonObject>(frame.content)
                                val id = args["controlId"]?.jsonPrimitive?.content
                                val value = args["value"]?.jsonPrimitive?.float
                                
                                if (id != null && value != null) {
                                    send(AgentAction(ActionType.CONTROL, details = listOf(id, value.toString())))
                                }
                            }
                            "repl_execute" -> {
                                val args = json.decodeFromString<JsonObject>(frame.content)
                                val code = args["code"]?.jsonPrimitive?.content
                                if (code != null) {
                                    send(AgentAction(ActionType.REPL, details = listOf(code)))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parse errors from tools
                    }
                }
                else -> {} 
            }
            if (frame is StreamFrame.End) {
                textChannel.close()
            }
        }
        textChannel.close()
    }
}
