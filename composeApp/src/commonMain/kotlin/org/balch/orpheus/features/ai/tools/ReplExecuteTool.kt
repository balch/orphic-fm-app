package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.tidal.ReplResult
import org.balch.orpheus.core.tidal.TidalRepl
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.features.ai.PanelId
import org.balch.orpheus.features.ai.ReplCodeEventBus

/**
 * Tool for executing REPL code patterns.
 * 
 * This tool automatically expands the CODE panel and populates it with the executed code.
 */
@SingleIn(AppScope::class)
class ReplExecuteTool @Inject constructor(
    private val tidalRepl: TidalRepl,
    private val replCodeEventBus: ReplCodeEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus
) : Tool<ReplExecuteTool.Args, ReplExecuteTool.Result>(

    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "repl_execute",
    description = """
        Execute Tidal-style REPL code for musical patterns.
        Use slots d1-d8 for different pattern layers.
        
        IMMEDIATE vs CYCLED COMMANDS:
        - Bare control commands (once $) are applied ONCE immediately: drive:0.45
        - Slot assignment (d1 $) cycles the pattern every beat: d1 $ drive:0.45
        - Use bare commands for static settings, slots for rhythmic patterns!
        
        PATTERN TYPES:
        - note "<notes>" - Melodic notes (octaves 2-6, sharps: c#3, flats: db3)
        - voices:<indices> - Trigger voice envelopes (indices 1-8)
        
        VOICE CONTROLS (voice index 1-8):
        - hold:<voice> <val> - Voice sustain level (0.0-1.0)
        - tune:<voice> <val> - Voice pitch (0.5 = unity)
        - pan:<voice> <val> - Voice pan (-1.0 to 1.0)
        - envspeed:<voice> <val> - Envelope speed (0.0=fast, 1.0=slow)
        
        QUAD CONTROLS (quad index 1-3):
        - quadhold:<quad> <val> - Quad hold level (0.0-1.0)
        - quadpitch:<quad> <val> - Quad pitch (0.5 = unity)
        
        DUO CONTROLS (duo/pair index 1-4):
        - duomod:<duo> <source> - Mod source: fm, off, or lfo
        - sharp:<pair> <val> - Waveform sharpness 0=tri, 1=sq
        
        EFFECTS (usually set once, not cycled):
        - drive:<val> OR distortion:<val> - Distortion (0.0-1.0)
        - vibrato:<val> - LFO depth (0.0-1.0)
        - feedback:<val> - Delay feedback (0.0-1.0)
        - delay:<val> OR delaymix:<val> - Delay mix (0.0-1.0)
        - volume:<val> - Master volume (0.0-1.0)
        
        TRANSFORMATIONS:
        - fast <n> <pattern> - Speed up by factor n
        - slow <n> <pattern> - Slow down by factor n
        
        COMBINING PATTERNS:
        Use # to combine patterns on one line.
        
        EXAMPLES:
        d1 $ note "c2 db2 g2 ab2"
        d2 $ voices:1 2 3 4
        d3 $ hold:1 0.8 # tune:1 0.5
        
        Use hush to silence all patterns.
    """.trimIndent()
) {
    private val log = logging("ReplExecuteTool")

    @Serializable
    data class Args(
        @property:LLMDescription("""
            REPL code. Each line:
                once $ <command> - immediate execution once
                d<slot> $ <pattern>
            Notes: d1 $ note "c3 e3"
            Voices: d1 $ voices:1 2 3 4
            Hold: d2 $ hold:1 0.8
            Effects: once $ drive:0.5
            Combine: d1 $ note "c3" # hold:1 0.8
        """)
        val code: String,
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String,
        val activeSlots: List<String> = emptyList()
    )

    override suspend fun execute(args: Args): Result {
        val code = args.code.trim()
        log.info { "ReplExecuteTool: Executing: ${code.take(80)}..." }
        
        log.debug { "ReplExecuteTool: Expanding CODE panel" }
        panelExpansionEventBus.expand(PanelId.CODE)
        replCodeEventBus.emitGenerating()

        // Handle hush command specially
        if (code.equals("hush", ignoreCase = true)) {
            log.debug { "ReplExecuteTool: Executing hush" }
            tidalRepl.hush()
            replCodeEventBus.emitGenerated("hush", emptyList())
            return Result(
                success = true,
                message = "All patterns silenced",
                activeSlots = emptyList()
            )
        }
        
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
            log.info { "ReplExecuteTool: Code executed successfully, slots=$slots" }
            replCodeEventBus.emitGenerated(code, slots.toList())
        } else {
            log.warn { "ReplExecuteTool: Failed - $resultMessage" }
            replCodeEventBus.emitFailed(resultMessage)
        }
        
        return Result(
            success = resultSuccess,
            message = resultMessage,
            activeSlots = slots.toList()
        )
    }
}
