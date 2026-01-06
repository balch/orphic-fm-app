package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
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
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
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
        - Bare control commands are applied ONCE immediately: drive:0.45, envspeed:1 0.8
        - Slot assignment (d1 $) cycles the pattern every beat: d1 $ note "c3"
        - Use bare commands for static settings, slots for rhythmic patterns!
        - WRONG: once $ drive:0.5 ("once $" does NOT work!)
        - CORRECT: drive:0.5 (bare command, no prefix)
        
        PATTERN TYPES:
        - note "<notes>" - Melodic notes (octaves 2-6, sharps: c#3, flats: db3)
        - voices:<indices> - Trigger voice envelopes (indices 1-8)
        - hold "<values>" - Per-voice hold pattern for use with # combiner
        
        VOICE CONTROLS (voice index 1-8):
        - hold:<voice> <val> - Voice sustain level (0.0-1.0)
        - tune:<voice> <val> - Voice pitch (see TUNING TO NOTES below)
        - pan:<voice> <val> - Voice pan (-1.0 to 1.0)
        - envspeed:<voice> <val> - Envelope speed (0.0=fast, 1.0=slow)
        
        TUNING VOICES TO MUSICAL NOTES:
        The tune command uses 0.0-1.0 where 0.5 = A3 (220Hz).
        Formula: tuneValue = 0.5 + (semitones from A3 / 48.0)
        
        Common note values:
        - A3 (unity) = 0.500
        - C4 (+3 semi) = 0.562
        - D4 (+5 semi) = 0.604
        - E4 (+7 semi) = 0.646
        - G4 (+10 semi) = 0.708
        - A4 (+12 semi) = 0.750
        
        Voice pitch multipliers affect final pitch:
        - Voices 1-2: 0.5× (one octave lower)
        - Voices 3-6: 1.0× (as calculated)
        - Voices 7-8: 2.0× (one octave higher, so tune=0.5 = A4/440Hz)
        
        Examples:
        - tune:3 0.562 → Voice 3 plays C4
        - tune:7 0.5 → Voice 7 plays A4 (concert pitch, due to 2x multiplier)
        
        **IMPORTANT - envspeed requires hold:**
        When using envspeed, you MUST also set hold or the note won't be heard!
        - WRONG: d1 $ voices "1" # envspeed "0.7"
        - CORRECT: d1 $ voices "1" # hold "0.8" # envspeed "0.7"
        
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
        
        TRANSFORMATIONS:
        - fast <n> <pattern> - Speed up by factor n
        - slow <n> <pattern> - Slow down by factor n
        
        COMBINING PATTERNS (#):
        Use # to combine patterns. Per-voice hold example:
        d1 $ voices "1 2 3" # hold "0.2 0.5 0.8"
        
        EXAMPLES:
        d1 $ note "c2 db2 g2 ab2"
        d2 $ voices:1 2 3 4
        d3 $ voices "1" # hold "0.8" # envspeed "0.7"
        tune:3 0.562
        
        Use hush to silence all patterns.
    """.trimIndent()
) {
    private val log = logging("ReplExecuteTool")

    @Serializable
    data class Args(
        @property:LLMDescription("""
            REPL code. Each line is either:
            - Bare command (applied immediately): drive:0.5, envspeed:1 0.8, hold:1 0.9
            - Slot pattern (cycled): d<slot> $ <pattern>
            Examples:
            drive:0.5
            envspeed:1 0.8
            hold:1 0.9
            d1 $ note "c3 e3"
            d2 $ voices "1 2 3" # hold "0.2 0.5 0.8"
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
        log.debug { "ReplExecuteTool: Executing: ${code.take(80)}..." }
        
        // Handle hush command specially
        if (code.equals("hush", ignoreCase = true)) {
            log.debug { "ReplExecuteTool: Executing hush" }
            tidalRepl.hush()
            // Don't emit Generated for hush - we don't want to update the UI with "hush" text
            return Result(
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
        
        return Result(
            success = resultSuccess,
            message = resultMessage,
            activeSlots = slots.toList()
        )
    }
}
