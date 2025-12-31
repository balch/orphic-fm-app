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
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.prompt.llm.LLModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.ai.AiModelProvider
import kotlin.time.ExperimentalTime

/**
 * Configuration for the Orpheus AI agent persona and behavior.
 */
@SingleIn(AppScope::class)
class OrpheusAgentConfig @Inject constructor(
    private val toolSet: Set<Tool<*, *>>,
    private val aiModelProvider: AiModelProvider,
) {
    @OptIn(ExperimentalTime::class)
    val toolRegistry = ToolRegistry {
        tools(toolSet.toList())
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
        6. **Adhere to User Command**: Understand the user's intent and control the synthesizer accordingly.
        
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
        - FX: `drive:0.5`, `vibrato:0.4`, `feedback:0.6`, `envspeed:1 0.8`, `hold:1 0.9`
        - Transformations: `slow 2`, `fast 4`
        
        **Pattern Combiners (#):**
        Use `#` to combine patterns. For example, to set per-voice hold levels:
        - `d1 $ voices "1 2 3" # hold "0.2 0.5 0.8"` -> voice 1 gets hold 0.2, voice 2 gets hold 0.5, voice 3 gets hold 0.8
        
        **IMPORTANT - envspeed requires hold:**
        When using `envspeed` (slow envelope), you MUST also set `hold` or the note will not sustain long enough to be heard!
        - WRONG: `d1 $ voices "1" # envspeed "0.7"` -> note may be inaudible
        - CORRECT: `d1 $ voices "1" # hold "0.8" # envspeed "0.7"` -> sustained note with slow envelope
        
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

    fun getReplPrompt(
        selectedMood: String,
        selectedMode: String,
        selectedKey: String,
    ):String = """
        Create a $selectedMood ambient drone soundscape in ${selectedKey.lowercase()} $selectedMode using repl_execute.
        
        Generate a SINGLE repl_execute call with MULTIPLE lines that include:
       
        Create an entertaining song using techniques from the below examples. 
        ```
            Example - Song setup parameters:
            - once $ drive:0.3 to 0.6 - warm distortion
            - once $ vibrato:0.3 to 0.5 - gentle LFO modulation
            - once $ feedback:0.5 to 0.8 - lush delay echoes  
            
            Example - SOUND LAYERS:
            - d1: Low drone notes based on $selectedKey $selectedMode (e.g., note "${selectedKey.lowercase()}2 ...") 
            - d2: Mid-range harmony notes
            - d3: Voice cycling (e.g., slow 2 voices:1 2 3 4)
            
            Example - SYNTH CONTROLS -  (use built in synth controls to provide more atmosphere):
            - d4: hold:1 0.8 - sustain voice 1
            
            Example format:
            once $ drive:0.4
            once $ vibrato:0.35
            once $ feedback:0.65
            d1 $ slow 2 note "${selectedKey.lowercase()}2 ..."
            d2 $ note "${selectedKey.lowercase()}3 ..."
            d3 $ slow 4 voices:2 3 4
        ```
        Make it $selectedMood. After execution, describe the atmosphere in one or two sentences.
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
