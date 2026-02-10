package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.tidal.ReplCodeEventBus
import org.balch.orpheus.core.tidal.ReplResult
import org.balch.orpheus.core.tidal.TidalRepl
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.features.ai.PanelId

@LLMDescription("Arguments for executing REPL code. Provide an array of lines to execute together as one block.")
@Serializable
data class ReplExecuteArgs(
    @property:LLMDescription("""
        Array of code lines to execute. Each element is a separate line of REPL code.
        All lines are joined with newlines and executed together as one atomic block.
        
        IMPORTANT: Use an array with one element per line of code!
        
        Example - Single line:
        ["d1 $ note \"c3 e3 g3\""]
        
        Example - Multiple lines (CORRECT way to send multi-line code):
        [
            "d1 $ note \"c2 db2 g2 ab2\"",
            "d2 $ voices:1 2 3 4",
            "d3 $ slow 2 $ voices \"1\" # envspeed \"0.3\"",
            "tune:3 0.562"
        ]
        
        Each line can be:
        - Slot patterns: "d1 $ note \"c3 e3\"" (cycled every beat)
        - Control commands: "drive:0.5" (applied once immediately)
        - Voice patterns: "d2 $ voices:1 2 3 4"
        - Tuning: "tune:3 0.562"
    """)
    val lines: List<String>
)

@LLMDescription("Result of REPL code execution with success status and active slot information.")
@Serializable
data class ReplExecuteResult(
    @property:LLMDescription("True if the code executed successfully, false if there was an error.")
    val success: Boolean,
    
    @property:LLMDescription("Human-readable message describing the result or error.")
    val message: String,
    
    @property:LLMDescription("List of active slot IDs (d1, d2, etc.) that now have running patterns.")
    val activeSlots: List<String> = emptyList()
)


/**
 * Tool for executing REPL code patterns.
 * 
 * This tool automatically expands the CODE panel and populates it with the executed code.
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class ReplExecuteTool @Inject constructor(
    private val tidalRepl: TidalRepl,
    private val replCodeEventBus: ReplCodeEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus
) : Tool<ReplExecuteArgs, ReplExecuteResult>(
    argsSerializer = ReplExecuteArgs.serializer(),
    resultSerializer = ReplExecuteResult.serializer(),
    name = "repl_execute",
    description = """
        Execute Tidal-style REPL code for musical patterns.

        IMPORTANT: Pass code as an array of strings in the 'lines' parameter!
        Each array element = one line of code. Lines are joined and executed together.
        Use slots d1-d16 for different pattern layers.

        === IMMEDIATE vs CYCLED ===
        - Bare commands apply ONCE immediately: drive:0.45, tune:1 0.562
        - Slot patterns (d1 $) cycle every beat: d1 $ note "c3 e3"
        - WRONG: once $ drive:0.5  |  CORRECT: drive:0.5

        === PATTERN TYPES ===
        note "<notes>"     - Melodic notes (c3, d#4, bb2, octaves 0-9)
        s "<samples>"      - Drum samples: bd, sn, hh, cp, oh, kick, hat, rim, lt, mt, ht, cb
        sound "<samples>"  - Same as s
        voices:<indices>   - Trigger voice envelopes (1-12)
        gates:<indices>    - Same as voices
        hold "<values>"    - Sustain levels per voice (0.0-1.0)

        === MINI-NOTATION (inside "quotes") ===
        [a b c]    - Subdivide: fit a b c into one step
        <a b c>    - Alternate: cycle through on successive beats
        a*3        - Speed up: play 'a' 3 times in its slot
        a/2        - Slow down: play 'a' every other cycle
        a!3        - Replicate: repeat 'a' 3 times as separate events
        a@2        - Elongate: 'a' takes 2 time units
        a?         - Degrade: 50% chance of silence
        a(3,8)     - Euclidean: 3 pulses evenly across 8 steps
        a(3,8,2)   - Euclidean with rotation offset 2
        0..4       - Range: expands to 0 1 2 3 4
        ~          - Silence/rest
        a,b        - Stack: play a and b simultaneously

        === # COMBINER ===
        Stacks two patterns together (applies both simultaneously):
        d1 $ note "c3 e3" # hold "0.8 0.2"
        d2 $ voices "1 2 3" # envspeed "0.9 0.5 0.2"

        === VOICE CONTROLS (index 1-8) ===
        tune:<voice> <val>      - Pitch (0.5=A3, formula: 0.5 + semitones/48)
        pan:<voice> <val>       - Pan (-1.0 to 1.0)
        envspeed:<voice> <val>  - Envelope (0=fast/percussive, 1=slow/drone)

        Tune reference: A3=0.500, C4=0.562, D4=0.604, E4=0.646, G4=0.708, A4=0.750
        Voice multipliers: 1-2=0.5x, 3-6=1.0x, 7-8=2.0x

        === QUAD CONTROLS (index 1-3) ===
        quadhold:<quad> <val>   - Sustain level (0.0-1.0)
        quadpitch:<quad> <val>  - Group pitch (0.5=unity)

        === DUO/PAIR CONTROLS (index 1-4) ===
        duomod:<duo> <source>   - Mod source: fm, off, or lfo
        sharp:<pair> <val>      - Waveform (0=triangle, 1=square)
        engine:<pair> <name>    - Synthesis engine (see ENGINE NAMES)

        ENGINE NAMES: osc, fm, noise, wave, va, additive, grain, string, modal, particle, swarm, chord, wavetable, speech

        === EFFECTS (set once, not cycled) ===
        drive:<val>             - Distortion drive (0.0-1.0)
        distortion:<val>        - Same as drive
        distmix:<val>           - Distortion mix (0.0-1.0)
        vibrato:<val>           - LFO depth (0.0-1.0)
        feedback:<val>          - Delay feedback (0.0-1.0)
        delaymix:<val>          - Delay wet/dry (0.0-1.0)

        === TRANSFORMATIONS ===
        fast <n> $ <pattern>    - Speed up by factor n
        slow <n> $ <pattern>    - Slow down by factor n
        stack [p1, p2, ...]     - Play patterns simultaneously
        fastcat [p1, p2, ...]   - Concatenate into one cycle
        slowcat [p1, p2, ...]   - Each pattern gets full cycle

        === META COMMANDS ===
        hush           - Silence all patterns
        bpm <value>    - Set tempo
        solo d1        - Solo a slot
        mute d1        - Mute a slot
        unmute d1      - Unmute a slot

        === MUSICAL EXAMPLES ===

        Ambient pad with delay:
        { "lines": ["bpm 80", "drive:0.3", "feedback:0.5", "delaymix:0.3", "d1 $ slow 2 $ note \"c3 e3 g3 b3\"", "d2 $ slow 4 $ note \"<c2 g2> <e2 b2>\""] }

        Drums with melody:
        { "lines": ["bpm 130", "d1 $ s \"bd ~ sn ~\"", "d2 $ s \"~ hh ~ hh\"", "d3 $ note \"c3 e3 g3 c4\""] }

        Euclidean polyrhythm:
        { "lines": ["d1 $ note \"c3(3,8) e3(5,8) g3(7,8)\"", "d2 $ s \"bd(3,8) sn(5,16) hh(7,12)\""] }

        Engine selection + effects:
        { "lines": ["engine:1 string", "sharp:1 0.3", "drive:0.2", "d1 $ note \"c3 e3 g3 c4\""] }
    """.trimIndent()
) {
    private val log = logging("ReplExecuteTool")

    override suspend fun execute(args: ReplExecuteArgs): ReplExecuteResult {
        // Join lines into a single code block
        val code = args.lines.joinToString("\n").trim()
        log.debug { "ReplExecuteTool: Executing ${args.lines.size} lines: ${code.take(80)}..." }
        
        // Handle hush command specially (if single line is just "hush")
        if (args.lines.size == 1 && code.equals("hush", ignoreCase = true)) {
            log.debug { "ReplExecuteTool: Executing hush" }
            tidalRepl.hush()
            // Don't emit Generated for hush - we don't want to update the UI with "hush" text
            return ReplExecuteResult(
                success = true,
                message = "All patterns silenced",
                activeSlots = emptyList()
            )
        }
        
        log.debug { "ReplExecuteTool: Expanding CODE panel" }
        panelExpansionEventBus.expand(PanelId.CODE)
        replCodeEventBus.emitGenerating()
        
        log.debug { "ReplExecuteTool: Evaluating REPL code" }
        val result = tidalRepl.evaluateSuspend(code)
        
        val resultSuccess: Boolean
        val resultMessage: String
        val slots: Set<String>
        
        when (result) {
            is ReplResult.Success -> {
                log.debug { "ReplExecuteTool: Success, slots=${result.slots}" }
                resultSuccess = true
                resultMessage = "Pattern executed successfully"
                slots = result.slots
            }
            is ReplResult.Error -> {
                log.warn { "ReplExecuteTool: Error - ${result.message}" }
                resultSuccess = false
                resultMessage = result.message
                slots = emptySet()
            }
        }
        
        if (resultSuccess) {
            log.debug { "ReplExecuteTool: Code executed successfully, slots=$slots" }
            replCodeEventBus.emitGenerated(code, slots.toList())
        } else {
            log.warn { "ReplExecuteTool: Failed - $resultMessage" }
            replCodeEventBus.emitFailed(resultMessage)
        }
        
        return ReplExecuteResult(
            success = resultSuccess,
            message = resultMessage,
            activeSlots = slots.toList()
        )
    }
}
