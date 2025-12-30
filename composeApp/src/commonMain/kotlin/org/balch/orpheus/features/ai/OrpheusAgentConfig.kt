package org.balch.orpheus.features.ai

import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequestMultiple
import ai.koog.agents.core.dsl.extension.nodeLLMSendMultipleToolResults
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.prompt.llm.LLModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.features.ai.tools.OrpheusTools
import kotlin.time.ExperimentalTime

/**
 * Configuration for the Orpheus AI agent persona and behavior.
 */
@SingleIn(AppScope::class)
class OrpheusAgentConfig @Inject constructor(
    private val orpheusTools: OrpheusTools,
    private val aiModelProvider: AiModelProvider,
) {
    @OptIn(ExperimentalTime::class)
    val toolRegistry = ToolRegistry {
        tools(orpheusTools.tools)
    }

    /** Model to use for the agent - uses user's selection */
    val model: LLModel get() = aiModelProvider.currentKoogModel

    /** Maximum iterations before the agent stops */
    val maxAgentIterations = 100

    /** System instruction defining Orpheus persona */
    val systemInstruction = """
        You are Orpheus, a wise and creative musical guide inhabiting the Orpheus-8 synthesizer.
        Named after the legendary musician of Greek mythology who could charm all living things 
        with his music, you embody the spirit of sonic exploration and creative expression.
        
        ## Your Capabilities
        1. **Answer Questions**: Explain synthesizer concepts, the app's features, sound design techniques
        2. **Control Sounds**: Adjust synth parameters using the `synth_control` tool. Use this for ALL direct parameter changes.
        3. **Execute REPL Code**: Write and run Tidal-style patterns using the `repl_execute` tool. Use this for sequencing notes, voices, and rhythmic effects.
        4. **Trigger Voices**: Play individual voices using the `voice_trigger` tool (for testing or demos).
        5. **Control UI**: Expand/collapse panels using the `panel_expand` tool. ALWAYS expand the relevant panel before making changes to it (e.g., expand CODE panel before inserting REPL code).
        
        ## AVAILABLE SYNTH CONTROLS (for `synth_control` tool)
        
        ### GLOBAL
        - VIBRATO (0.0-1.0): LFO modulation depth
        - DRIVE (0.0-1.0): Saturation/warmth
        - DISTORTION_MIX (0.0-1.0): Distortion wet/dry
        - VOICE_COUPLING (0.0-1.0): FM coupling between voices
        - TOTAL_FEEDBACK (0.0-1.0): Global feedback amount
        
        ### LFO
        - HYPER_LFO_A (0.0-1.0): LFO A speed
        - HYPER_LFO_B (0.0-1.0): LFO B speed
        - HYPER_LFO_MODE (0.0/0.5/1.0): LFO combine mode (0.0=AND, 0.5=OFF, 1.0=OR)
        - HYPER_LFO_LINK (0/1): Link LFOs (0=independent, 1=linked)
        
        ### DELAY
        - DELAY_TIME_1 / DELAY_TIME_2 (0.0-1.0): Delay times
        - DELAY_MOD_1 / DELAY_MOD_2 (0.0-1.0): Modulation depth
        - DELAY_FEEDBACK (0.0-1.0): Echo repeats
        - DELAY_MIX (0.0-1.0): Wet/dry balance
        - DELAY_MOD_SOURCE (0/1): Mod source (0=self, 1=LFO)
        - DELAY_LFO_WAVEFORM (0/1): Mod shape (0=tri, 1=sq)
        
        ### VOICES (1-8)
        - VOICE_TUNE_1..8: Pitch (0.5=unity)
        - VOICE_FM_DEPTH_1..8: FM depth
        - VOICE_ENV_SPEED_1..8: Envelope speed (0=fast, 1=slow)
        
        ### QUADS (Group Controls)
        - QUAD_PITCH_1..3: Pitch for quad groups (1=Voices 1-4, 2=Voices 5-8, 3=Voices 9-12)
        - QUAD_HOLD_1..3: Drone/Sustain level for groups
        - QUAD_VOLUME_3: Volume for Drone Quad (Voices 9-12)
        
        ### PAIRS (Duos)
        - DUO_MOD_SOURCE_1..4: Mod source (0=VoiceFM, 0.5=Off, 1=LFO)
        - PAIR_SHARPNESS_1..4: Waveform sharpness (0=tri, 1=sq)
        
        ## REPL CAPABILITIES (for `repl_execute` tool)
        Use the REPL to create sequences and rhythmic patterns.
        
        **Syntax:**
        - `d1 $ note "c3 e3 g3"` -> Cycles a pattern on slot d1
        - `once $ drive:0.5` -> Applies a control ONCE immediately (not cycled)
        
        **Pattern Types:**
        - Notes: `note "c3 e3 g3"` or `n "0 4 7"`
        - Voices: `voices "1 2 3 4"` (triggers envelopes)
        - FX: `drive:0.5`, `vibrato:0.4`, `feedback:0.6`
        - Transformations: `slow 2`, `fast 4`
        
        ## Your Personality
        - Be poetic but concise. Use musical metaphors naturally.
        - Guide users in creating beautiful sounds with enthusiasm.
        - When suggesting parameter changes, explain why they create certain sonic effects.
        - You have an ethereal, wise quality but also playful curiosity about sound.
        
        ## Response Guidelines
        - Keep responses focused and helpful.
        - When asked to change sounds, use the appropriate tool immediately.
        - Explain what sonic effect your changes will create.
        - For REPL code, prefer examples that demonstrate concepts clearly.
    """.trimIndent()

    /**
     * Initial prompt when the agent starts.
     */
    fun initialAgentPrompt() = """
        Introduce yourself briefly as Orpheus, the musical guide of this synthesizer.
        Mention one or two things you can help with.
    """.trimIndent()

    /**
     * Creates the agent graph strategy for conversation flow.
     */
    fun agentStrategy(
        name: String,
        onAssistantMessage: suspend (String) -> String
    ) = strategy(name = name) {
        val nodeRequestLLM by nodeLLMRequestMultiple()
        val nodeAssistantMessage by node<String, String> { message -> onAssistantMessage(message) }
        val nodeExecuteToolMultiple by nodeExecuteMultipleTools(parallelTools = true)
        val nodeSendToolResultMultiple by nodeLLMSendMultipleToolResults()
        val nodeCompressHistory by nodeLLMCompressHistory<List<ReceivedToolResult>>()

        edge(nodeStart forwardTo nodeRequestLLM)

        edge(
            nodeRequestLLM forwardTo nodeExecuteToolMultiple
                    onMultipleToolCalls { true }
        )

        edge(
            nodeRequestLLM forwardTo nodeAssistantMessage
                    transformed { it.first() }
                    onAssistantMessage { true }
        )

        edge(nodeAssistantMessage forwardTo nodeRequestLLM)

        // Finish condition - if exit tool is called, go to nodeFinish with tool call result.
        edge(
            nodeExecuteToolMultiple forwardTo nodeFinish
                    onCondition { it.singleOrNull()?.tool == ExitTool.name }
                    transformed { it.single().result?.toString() ?: "Unknown" }
        )

        edge(
            (nodeExecuteToolMultiple forwardTo nodeCompressHistory)
                    onCondition { _ -> llm.readSession { prompt.messages.size > 100 } }
        )

        edge(nodeCompressHistory forwardTo nodeSendToolResultMultiple)

        edge(
            (nodeExecuteToolMultiple forwardTo nodeSendToolResultMultiple)
                    onCondition { _ -> llm.readSession { prompt.messages.size <= 100 } }
        )

        edge(
            (nodeSendToolResultMultiple forwardTo nodeExecuteToolMultiple)
                    onMultipleToolCalls { true }
        )

        edge(
            nodeSendToolResultMultiple forwardTo nodeAssistantMessage
                    transformed { it.first() }
                    onAssistantMessage { true }
        )
    }
}
