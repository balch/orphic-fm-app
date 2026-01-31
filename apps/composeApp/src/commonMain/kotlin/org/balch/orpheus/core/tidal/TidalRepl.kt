package org.balch.orpheus.core.tidal

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.util.currentTimeMillis

/**
 * Console entry for REPL output.
 */
data class ConsoleEntry(
    val timestamp: Long,
    val type: ConsoleType,
    val message: String,
    val codeSnippet: String? = null
)

enum class ConsoleType {
    INFO,
    SUCCESS,
    ERROR,
    PATTERN
}

/**
 * Result of evaluating code in the REPL.
 */
sealed class ReplResult {
    data class Success(val slots: Set<String>, val message: String) : ReplResult()
    data class Error(val message: String, val line: Int? = null) : ReplResult()
}

/**
 * Snapshot for undo support.
 */
private data class ReplSnapshot(
    val slots: Map<String, Pattern<TidalEvent>>,
    val timestamp: Long
)

/**
 * Evaluation mode for REPL code.
 */
enum class EvalMode {
    /**
     * Merge new patterns with existing ones. 
     * Use this for executing partial code blocks/lines without stopping other patterns.
     */
    MERGE,
    
    /**
     * Replace existing patterns with new ones.
     * Use this for executing the full file or when you want to clear unmentioned slots.
     */
    REPLACE
}

/**
 * REPL orchestrator for Tidal live coding.
 * 
 * Manages pattern slots (d1-d16), evaluation lifecycle, and console output.
 * All heavy work is dispatched to a background thread via coroutines.
 */
@SingleIn(AppScope::class)
@Inject
class TidalRepl(
    private val scheduler: TidalScheduler,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val log = logging("TidalRepl")
    
    // Use injected dispatcher for parsng work (allows testing)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    
    // Pattern slots (d0-d15)
    private val slots = mutableMapOf<String, Pattern<TidalEvent>>()
    private val mutedSlots = mutableSetOf<String>()
    private var soloSlot: String? = null
    
    // Evaluation history for undo
    private val history = mutableListOf<ReplSnapshot>()
    private val maxHistorySize = 20
    
    // Console output
    private val _console = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = _console.asStateFlow()
    
    // Active slots
    private val _activeSlots = MutableStateFlow<Set<String>>(emptySet())
    val activeSlots: StateFlow<Set<String>> = _activeSlots.asStateFlow()
    
    /**
     * Evaluate code and update patterns.
     * This is dispatched to a background thread for parsing.
     * 
     * @param code The Tidal code to evaluate
     * @param mode Evaluation mode (MERGE or REPLACE)
     * @param onResult Callback for the result
     */
    fun evaluate(
        code: String, 
        mode: EvalMode = EvalMode.REPLACE, 
        onResult: (ReplResult) -> Unit = {}
    ) {
        scope.launch {
            val result = evaluateInternal(code, mode)
            onResult(result)
        }
    }
    
    /**
     * Evaluate code and update patterns (suspend version).
     * Use this from coroutines/suspend functions to await the result.
     */
    suspend fun evaluateSuspend(
        code: String, 
        mode: EvalMode = EvalMode.REPLACE
    ): ReplResult {
        return withContext(dispatcherProvider.default) {
            evaluateInternal(code, mode)
        }
    }
    
    /**
     * Sanitize AI-generated code to handle common artifacts.
     * - Converts smart/curly quotes to straight quotes
     * - Handles escaped quotes from JSON (\" → ")  
     * - Removes zero-width characters
     * - Normalizes line endings
     */
    private fun sanitizeCode(code: String): String {
        return code
            // Normalize line endings
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // Convert smart/curly quotes to straight quotes (using Unicode escapes)
            .replace('\u201C', '"')  // Left double quote "
            .replace('\u201D', '"')  // Right double quote "
            .replace('\u2018', '\'') // Left single quote '
            .replace('\u2019', '\'') // Right single quote '
            // Handle escaped quotes from JSON (common AI artifact)
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            // Remove zero-width characters that can sneak in
            .replace("\u200B", "") // Zero-width space
            .replace("\u200C", "") // Zero-width non-joiner
            .replace("\u200D", "") // Zero-width joiner
            .replace("\uFEFF", "") // BOM
    }
    
    /**
     * Evaluate code synchronously (for internal use).
     * 
     * Supports MERGE (partial update) and REPLACE (full update) modes.
     * REPLACE mode enables removing patterns by deleting code.
     */
    private fun evaluateInternal(code: String, mode: EvalMode): ReplResult {
        // Sanitize AI-generated code artifacts
        val sanitizedCode = sanitizeCode(code)
        
        log.debug { "Evaluating REPL code (mode=$mode):\n$sanitizedCode" }
        
        if (sanitizedCode.isBlank()) {
            log.warn { "Empty code after sanitization" }
            logConsole(ConsoleType.ERROR, "No code to evaluate")
            return ReplResult.Error("No code to evaluate")
        }
        
        // Save snapshot for undo
        saveSnapshot()
        
        // Remember which slots existed before this evaluation
        val previousSlots = slots.keys.toSet()
        
        return try {
            // Use original code without trim to preserve character offsets matching the UI
            val modifiedSlots = mutableSetOf<String>()
            
            // Track character offset as we process each line
            var currentOffset = 0
            
            for ((lineNum, line) in sanitizedCode.lines().withIndex()) {
                val lineOffset = currentOffset
                currentOffset += line.length + 1 // +1 for newline
                
                var trimmed = line.trim()
                
                // Skip blank lines and full-line comments
                if (trimmed.isBlank() || trimmed.startsWith("//") || trimmed.startsWith("#")) continue
                
                // Strip trailing comments (-- or // style) that AI sometimes adds
                // Be careful not to strip quotes that might contain these characters
                val dashDashIndex = trimmed.indexOf(" --")
                if (dashDashIndex > 0 && !trimmed.substring(0, dashDashIndex).contains(Regex("\"[^\"]*$"))) {
                    trimmed = trimmed.substring(0, dashDashIndex).trim()
                }
                val slashSlashIndex = trimmed.indexOf(" //")
                if (slashSlashIndex > 0 && !trimmed.substring(0, slashSlashIndex).contains(Regex("\"[^\"]*$"))) {
                    trimmed = trimmed.substring(0, slashSlashIndex).trim()
                }
                
                // Handle commands
                when {
                    trimmed == "hush" -> {
                        hush()
                        return ReplResult.Success(emptySet(), "Hushed all patterns")
                    }
                    
                    trimmed.startsWith("solo ") -> {
                        val slot = trimmed.substringAfter("solo ").trim()
                        solo(slot)
                        continue
                    }
                    
                    trimmed.startsWith("mute ") -> {
                        val slot = trimmed.substringAfter("mute ").trim()
                        mute(slot)
                        continue
                    }
                    
                    trimmed.startsWith("unmute ") -> {
                        val slot = trimmed.substringAfter("unmute ").trim()
                        unmute(slot)
                        continue
                    }
                    
                    // bpm command - set tempo
                    trimmed.startsWith("bpm ") || trimmed.startsWith("bpm:") -> {
                        val bpmStr = if (trimmed.startsWith("bpm:")) {
                            trimmed.substringAfter("bpm:").trim()
                        } else {
                            trimmed.substringAfter("bpm ").trim()
                        }
                        val bpmValue = bpmStr.replace("\"", "").replace("'", "").toDoubleOrNull()
                        if (bpmValue != null && bpmValue > 0) {
                            scheduler.setBpm(bpmValue)
                            logConsole(ConsoleType.INFO, "bpm: $bpmValue")
                        } else {
                            logConsole(ConsoleType.ERROR, "Invalid BPM value: $bpmStr")
                        }
                        continue
                    }
                    
                    // "once $" prefix - execute pattern immediately without cycling
                    trimmed.startsWith("once ") || trimmed.startsWith("once$") -> {
                        val patternCode = if (trimmed.startsWith("once $")) {
                            trimmed.substringAfter("once $").trim()
                        } else if (trimmed.startsWith("once$")) {
                            trimmed.substringAfter("once$").trim()
                        } else {
                            trimmed.substringAfter("once ").trim()
                        }
                        val patternOffset = lineOffset + line.indexOf(patternCode)
                        val pattern = parsePattern(patternCode, lineNum + 1, patternOffset)
                        // Execute immediately by dispatching all events at t=0
                        val events = pattern.query(Arc(0.0, 1.0))
                        events.forEach { event ->
                            scheduler.dispatchEventImmediate(event.value)
                        }
                        logConsole(ConsoleType.INFO, "once: $patternCode")
                        continue
                    }
                }
                
                // Pattern assignment: d1 $ pattern or d1 = pattern
                val assignMatch = Regex("^(d\\d+)\\s*[\\$=]\\s*(.+)$").find(trimmed)
                if (assignMatch != null) {
                    val (slotName, patternCode) = assignMatch.destructured
                    // Calculate offset to pattern code within the line
                    val patternOffset = lineOffset + line.indexOf(trimmed) + trimmed.indexOf(patternCode)
                    val pattern = parsePattern(patternCode, lineNum + 1, patternOffset)
                    slots[slotName] = pattern
                    modifiedSlots.add(slotName)
                } else {
                    // Check if this is a bare control command (immediate set, not cycled)
                    // Control commands are recognized by their prefix patterns
                    // Supports both colon syntax (drive:0.4) and space syntax (drive 0.4)
                    val controlNames = "drive|distortion|vibrato|feedback|delay|delaymix|distmix|" +
                        "hold|tune|pan|quadhold|quadpitch|duomod|sharp|envspeed|drum_|resonator_"
                    val isBareControlCommand = trimmed.matches(Regex(
                        "^($controlNames|[a-z0-9_]+)[:\\s][^$]+$"
                    ))
                    
                    if (isBareControlCommand) {
                        // Execute immediately - dispatch the control event directly
                        val patternOffset = lineOffset + line.indexOf(trimmed)
                        val pattern = parsePattern(trimmed, lineNum + 1, patternOffset)
                        val events = pattern.query(Arc(0.0, 1.0))
                        events.forEach { event ->
                            scheduler.dispatchEventImmediate(event.value)
                        }
                        logConsole(ConsoleType.INFO, "set: $trimmed")
                        continue
                    }
                    
                    // Anonymous pattern - goes to d0 (cycled)
                    val patternOffset = lineOffset + line.indexOf(trimmed)
                    val pattern = parsePattern(trimmed, lineNum + 1, patternOffset)
                    slots["d0"] = pattern
                    modifiedSlots.add("d0")
                }
            }
            
            
            // Clear slots that were previously active but are no longer in the code
            // Only do this in REPLACE mode (e.g. running full file)
            if (mode == EvalMode.REPLACE) {
                val slotsToRemove = previousSlots - modifiedSlots
                for (slotToRemove in slotsToRemove) {
                    slots.remove(slotToRemove)
                }
                if (slotsToRemove.isNotEmpty()) {
                    logConsole(ConsoleType.INFO, "Cleared: ${slotsToRemove.joinToString(", ")}")
                }
            }
            
            // Update scheduler with combined pattern
            updateScheduler()
            
            _activeSlots.value = slots.keys.toSet()
            
            val msg = "Evaluated: ${modifiedSlots.joinToString(", ")}"
            logConsole(ConsoleType.SUCCESS, msg, sanitizedCode.take(50))
            ReplResult.Success(modifiedSlots, msg)
            
        } catch (e: Exception) {
            val msg = e.message ?: "Parse error"
            log.error { "REPL parse error: $msg\nCode: ${sanitizedCode.take(200)}" }
            logConsole(ConsoleType.ERROR, msg, sanitizedCode.take(50))
            ReplResult.Error(msg)
        }
    }
    
    /**
     * Parse a pattern string into a Pattern.
     * @param code The pattern code for this line
     * @param lineNum The line number (1-indexed)
     * @param lineOffset Character offset of this line in the full code string
     */
    private fun parsePattern(code: String, lineNum: Int, lineOffset: Int = 0): Pattern<TidalEvent> {
        val trimmed = code.trim()
        val trimOffset = lineOffset + code.indexOf(trimmed) // offset where trimmed content starts
        
        // Check for TidalCycles `#` pattern combiner (split outside quotes)
        // e.g. note "c3 e3" # hold:0 0.8 -> stack both patterns
        val hashParts = splitByHashOutsideQuotes(trimmed)
        if (hashParts.size > 1) {
            log.debug { "Splitting pattern by #: ${hashParts.size} parts" }
            val patterns = hashParts.mapIndexed { index, part ->
                val partOffset = trimOffset + trimmed.indexOf(part.trim())
                parsePatternSingle(part.trim(), lineNum, partOffset)
            }
            return Pattern.stack(patterns)
        }
        
        return parsePatternSingle(trimmed, lineNum, trimOffset)
    }
    
    /**
     * Split a pattern string by `#` but only when `#` is outside of quotes.
     */
    private fun splitByHashOutsideQuotes(input: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '
        
        for (char in input) {
            when {
                char == '"' || char == '\'' -> {
                    if (!inQuotes) {
                        inQuotes = true
                        quoteChar = char
                    } else if (char == quoteChar) {
                        inQuotes = false
                    }
                    current.append(char)
                }
                char == '#' && !inQuotes -> {
                    val part = current.toString().trim()
                    if (part.isNotEmpty()) {
                        parts.add(part)
                    }
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        
        val lastPart = current.toString().trim()
        if (lastPart.isNotEmpty()) {
            parts.add(lastPart)
        }
        
        return parts
    }
    
    /**
     * Parse a single pattern (no `#` combiner).
     */
    private fun parsePatternSingle(trimmed: String, lineNum: Int, trimOffset: Int): Pattern<TidalEvent> {
        // Check for silence
        if (trimmed == "silence") {
             return Pattern.silence()
        }

        // Check for note patterns: note "c3 e3 g3", n "c3", note("..."), n("...")
        // Use non-greedy .+? to avoid capturing trailing delimiters
        val noteMatch = Regex("^(?:note|n)\\s*[\"(](.+?)[\"')]$").find(trimmed)
        if (noteMatch != null) {
            // Strip any stray quotes from the captured content (handles nested quotes)
            val notePattern = noteMatch.groupValues[1].replace("\"", "").replace("'", "")
            // Calculate offset: trimOffset + position of quote content in the match
            val quoteContentOffset = trimOffset + trimmed.indexOf(noteMatch.groupValues[1])
            return parseNotePattern(notePattern, quoteContentOffset)
        }
        
        // Check for sound patterns: s "bd sn" or sound "bd sn"
        // Use non-greedy .+? to avoid capturing trailing delimiters
        val soundMatch = Regex("^(?:s|sound)\\s*[\"(](.+?)[\"')]$").find(trimmed)
        if (soundMatch != null) {
            // Strip any stray quotes from the captured content
            val soundPattern = soundMatch.groupValues[1].replace("\"", "").replace("'", "")
            val quoteContentOffset = trimOffset + trimmed.indexOf(soundMatch.groupValues[1])
            return parseSoundPattern(soundPattern, quoteContentOffset)
        }
        
        // Check for voice patterns: voices:0 1 2 3 (also accepts gates: for legacy)
        if (trimmed.startsWith("voices:") || trimmed.startsWith("gates:")) {
            val prefix = if (trimmed.startsWith("voices:")) "voices:" else "gates:"
            val afterPrefix = trimmed.substringAfter(prefix)
            val voicesStr = afterPrefix.trim()
            // Calculate offset: account for whitespace between prefix and first token
            val whitespaceLen = afterPrefix.length - afterPrefix.trimStart().length
            val voicesOffset = trimOffset + prefix.length + whitespaceLen
            return parseVoicesPattern(voicesStr, voicesOffset)
        }
        
        // Handle unquoted "voices 1 2 3" or "gates 1 2 3"
        // This must check for space after command to avoid matching things like "voices2"
        if (trimmed.startsWith("voices ") || trimmed.startsWith("gates ")) {
            val prefix = if (trimmed.startsWith("voices ")) "voices " else "gates "
            val voicesStr = trimmed.substringAfter(prefix).trim()
            val hasQuotes = voicesStr.startsWith("\"") && voicesStr.endsWith("\"")
            
            // If quoted "1 2 3", strip quotes
            val cleanStr = if (hasQuotes) voicesStr.removeSurrounding("\"") else voicesStr
            
            val voicesOffset = trimOffset + prefix.length + (if (hasQuotes) 1 else 0)
            return parseVoicesPattern(cleanStr, voicesOffset)
        }
        
        // Tidal-style quoted voices: voices "0 1 2 3" (Legacy regex match - kept for robustness but redundant with above)
        val quotedVoicesMatch = Regex("^voices\\s*\"([^\"]+)\"$").find(trimmed)
        if (quotedVoicesMatch != null) {
            val voicesStr = quotedVoicesMatch.groupValues[1]
            val voicesOffset = trimOffset + trimmed.indexOf(voicesStr)
            return parseVoicesPattern(voicesStr, voicesOffset)
        }
        
        // Tidal-style hold pattern: hold "0.2 0.5 0" - sequence of hold values for voices 0, 1, 2...
        // This enables: d1 $ voices "1 2 3" # hold "0.2 0.5 0"
        val quotedHoldMatch = Regex("^hold\\s*\"([^\"]+)\"$").find(trimmed)
        if (quotedHoldMatch != null) {
            val holdStr = quotedHoldMatch.groupValues[1]
            val holdOffset = trimOffset + trimmed.indexOf(holdStr)
            return parseHoldPattern(holdStr, holdOffset)
        }
        
        // ============================================================
        // SYNTH CONTROL PATTERNS
        // ============================================================
        
        // ============================================================
        // SYNTH CONTROL PATTERNS
        // ============================================================
        
        // Helper for voice commands (index + value)
        // Supports: quoted "idx val", quoted "val" (implies voice 1), colon "idx val", space "idx val"
        fun extractVoiceParam(input: String, command: String): Pair<Int, Float>? {
            // 1. Quoted syntax
            val quotedMatch = Regex("^$command\\s*\"([^\"]+)\"$").find(input)
            if (quotedMatch != null) {
                val content = quotedMatch.groupValues[1].trim()
                val parts = content.split(Regex("\\s+"))
                if (parts.size >= 2) {
                     val v = parts[0].toIntOrNull()
                     val valF = parts[1].toFloatOrNull()
                     if (v != null && valF != null) return v to valF
                } else if (parts.isNotEmpty()) {
                     // Single value -> implicit voice 1
                     val valF = parts[0].toFloatOrNull()
                     if (valF != null) return 1 to valF
                }
                return null
            }
            
            // 2. Colon/Space syntax
            if (input.startsWith("$command:") || input.startsWith("$command ")) {
                 val content = if (input.startsWith("$command:")) input.substringAfter("$command:") else input.substringAfter("$command ")
                 val clean = content.trim()
                 // Split by space
                 val parts = clean.split(" ", limit = 2)
                 if (parts.size >= 2) {
                      val v = parts[0].replace("\"", "").replace("'", "").toIntOrNull()
                      val valF = parts[1].replace("\"", "").replace("'", "").toFloatOrNull()
                      if (v != null && valF != null) return v to valF
                 } else if (parts.isNotEmpty()) {
                     // Single value -> implicit voice 1
                     val valF = parts[0].replace("\"", "").replace("'", "").toFloatOrNull()
                     if (valF != null) return 1 to valF
                 }
            }
            return null
        }

        // hold:<voiceIndex> <value> or hold "..." - Voice sustain/hold level
        extractVoiceParam(trimmed, "hold")?.let { (voiceIndex, value) ->
            if (voiceIndex !in 1..8) throw IllegalArgumentException("Line $lineNum: Voice index must be 1-8, got: $voiceIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.VoiceHold(voiceIndex - 1, value.coerceIn(0f, 1f), listOf(location)))
        }

        // envspeed:<voiceIndex> <value> or envspeed "..." - Voice envelope speed
        extractVoiceParam(trimmed, "envspeed")?.let { (voiceIndex, value) ->
            if (voiceIndex !in 1..8) throw IllegalArgumentException("Line $lineNum: Voice index must be 1-8, got: $voiceIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.VoiceEnvSpeed(voiceIndex - 1, value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // Helper to extract value from both "command:value" and "command value" syntax
        fun extractValue(input: String, command: String): Float? {
            val colonSyntax = input.startsWith("$command:")
            val spaceSyntax = input.startsWith("$command ") && !input.startsWith("$command:")
            if (!colonSyntax && !spaceSyntax) return null
            val valueStr = if (colonSyntax) {
                input.substringAfter("$command:").trim()
            } else {
                input.substringAfter("$command ").trim()
            }
            return valueStr.replace("\"", "").replace("'", "").toFloatOrNull()
        }
        
        // drive:<value> or drive <value> - Distortion drive amount (0.0-1.0)
        // Also accepts distortion: as an alias (common AI guess)
        extractValue(trimmed, "drive")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.Drive(value.coerceIn(0f, 1f), listOf(location)))
        }
        extractValue(trimmed, "distortion")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.Drive(value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // vibrato:<value> or vibrato <value> - Vibrato/LFO depth (0.0-1.0)
        extractValue(trimmed, "vibrato")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.Vibrato(value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // feedback:<value> or feedback <value> - Delay feedback amount (0.0-1.0)
        extractValue(trimmed, "feedback")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.DelayFeedback(value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // delaymix:<value> or delaymix <value> - Delay wet/dry mix (0.0-1.0)
        // Also accepts delay: as an alias (common AI guess)
        extractValue(trimmed, "delaymix")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.DelayMix(value.coerceIn(0f, 1f), listOf(location)))
        }
        extractValue(trimmed, "delay")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.DelayMix(value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // distmix:<value> or distmix <value> - Distortion mix (0.0-1.0)
        extractValue(trimmed, "distmix")?.let { value ->
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.DistortionMix(value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // pan:<voiceIndex> <value> - Voice pan position (-1.0 to 1.0)
        extractVoiceParam(trimmed, "pan")?.let { (voiceIndex, value) ->
            if (voiceIndex !in 1..8) throw IllegalArgumentException("Line $lineNum: Voice index must be 1-8, got: $voiceIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.VoicePan(voiceIndex - 1, value.coerceIn(-1f, 1f), listOf(location)))
        }
        
        // tune:<voiceIndex> <value> - Voice pitch/tune (0.0-1.0, 0.5 = unity)
        extractVoiceParam(trimmed, "tune")?.let { (voiceIndex, value) ->
            if (voiceIndex !in 1..8) throw IllegalArgumentException("Line $lineNum: Voice index must be 1-8, got: $voiceIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.VoiceTune(voiceIndex - 1, value.coerceIn(0f, 1f), listOf(location)))
        }

        // quadhold:<quadIndex> <value> - Quad hold level (0.0-1.0)
        extractVoiceParam(trimmed, "quadhold")?.let { (quadIndex, value) ->
            if (quadIndex !in 1..3) throw IllegalArgumentException("Line $lineNum: Quad index must be 1-3, got: $quadIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.QuadHold((quadIndex - 1).coerceIn(0, 2), value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // quadpitch:<quadIndex> <value> - Quad pitch (0.0-1.0, 0.5 = unity)
        extractVoiceParam(trimmed, "quadpitch")?.let { (quadIndex, value) ->
            if (quadIndex !in 1..3) throw IllegalArgumentException("Line $lineNum: Quad index must be 1-3, got: $quadIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.QuadPitch((quadIndex - 1).coerceIn(0, 2), value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // duomod:<duoIndex> <source> - Duo modulation source (fm/off/lfo)
        if (trimmed.startsWith("duomod:") || trimmed.startsWith("duomod ")) {
            val content = if (trimmed.startsWith("duomod:")) trimmed.substringAfter("duomod:") else trimmed.substringAfter("duomod ")
            val clean = content.trim()
            val parts = clean.split(" ", limit = 2)
            if (parts.size >= 2) {
                val duoIndex = parts[0].replace("\"", "").replace("'", "").toIntOrNull()
                val source = parts[1].replace("\"", "").replace("'", "").lowercase()
                
                if (duoIndex != null) {
                    if (duoIndex !in 1..4) throw IllegalArgumentException("Line $lineNum: Duo index must be 1-4, got: $duoIndex")
                    if (source !in listOf("fm", "off", "lfo")) {
                        throw IllegalArgumentException("Line $lineNum: duomod source must be 'fm', 'off', or 'lfo'")
                    }
                    val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
                    return Pattern.pure(TidalEvent.DuoMod((duoIndex - 1).coerceIn(0, 3), source, listOf(location)))
                }
            }
        }
        
        // Handle quoted duomod "1 fm"
        Regex("^duomod\\s*\"([^\"]+)\"$").find(trimmed)?.let { match ->
            val content = match.groupValues[1].trim()
            val parts = content.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val duoIndex = parts[0].toIntOrNull()
                val source = parts[1].lowercase()
                if (duoIndex != null) {
                    if (duoIndex !in 1..4) throw IllegalArgumentException("Line $lineNum: Duo index must be 1-4, got: $duoIndex")
                    if (source !in listOf("fm", "off", "lfo")) {
                         throw IllegalArgumentException("Line $lineNum: duomod source must be 'fm', 'off', or 'lfo'")
                    }
                    val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
                    return Pattern.pure(TidalEvent.DuoMod((duoIndex - 1).coerceIn(0, 3), source, listOf(location)))
                }
            }
        }

        
        // sharp:<pairIndex> <value> - Pair waveform sharpness (0.0 = tri, 1.0 = sq)
        extractVoiceParam(trimmed, "sharp")?.let { (pairIndex, value) ->
            if (pairIndex !in 1..4) throw IllegalArgumentException("Line $lineNum: Pair index must be 1-4, got: $pairIndex")
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)
            return Pattern.pure(TidalEvent.PairSharp((pairIndex - 1).coerceIn(0, 3), value.coerceIn(0f, 1f), listOf(location)))
        }
        
        // Check for transformations
        if (trimmed.startsWith("fast ") || trimmed.startsWith("slow ")) {
            return parseTransformation(trimmed, lineNum, trimOffset)
        }
        
        // Check for stack function: stack [ ... ]
        if (isFunctionCall(trimmed, "stack")) {
            return parseListFunction("stack", trimmed, lineNum, trimOffset, Pattern.Companion::stack)
        }
        
        // Check for fastcat function: fastcat [ ... ]
        if (isFunctionCall(trimmed, "fastcat")) {
            return parseListFunction("fastcat", trimmed, lineNum, trimOffset, Pattern.Companion::fastcat)
        }

        // Check for cat function: cat [ ... ]
        if (isFunctionCall(trimmed, "cat")) {
            return parseListFunction("cat", trimmed, lineNum, trimOffset, Pattern.Companion::fastcat)
        }

        // Check for slowcat function: slowcat [ ... ]
        if (isFunctionCall(trimmed, "slowcat")) {
            return parseListFunction("slowcat", trimmed, lineNum, trimOffset, Pattern.Companion::slowcat)
        }

        // ============================================================
        // GENERIC CONTROL FALLBACK
        // ============================================================

        // Matches any "control_id:value" or "control_id value"
        // Also supports quoted "val1 val2" sequence for the control
        val genericControlMatch = Regex("^([a-z0-9_]+)[:\\s](.+)$").find(trimmed)
        if (genericControlMatch != null) {
            val controlId = genericControlMatch.groupValues[1]
            val content = genericControlMatch.groupValues[2].trim()
            val hasQuotes = content.startsWith("\"") && content.endsWith("\"")
            val cleanContent = if (hasQuotes) content.removeSurrounding("\"") else content

            val parts = cleanContent.split(Regex("\\s+"))
            val location = SourceLocation(trimOffset, trimOffset + trimmed.length)

            return if (parts.size > 1) {
                // Sequence of values
                Pattern.fastcat(parts.map { p ->
                    val v = p.toFloatOrNull() ?: 0f
                    Pattern.pure(TidalEvent.Control(controlId, v, listOf(location)))
                })
            } else {
                // Single value
                val v = cleanContent.toFloatOrNull() ?: 0f
                Pattern.pure(TidalEvent.Control(controlId, v, listOf(location)))
            }
        }
        
        // Default: try as gate pattern (just numbers)
        return when (val result = TidalParser.parseGates(trimmed)) {
            is TidalParser.ParseResult.Success -> result.pattern
            is TidalParser.ParseResult.Failure -> throw IllegalArgumentException("Line $lineNum: ${result.message}\nInput: '$trimmed'")
        }
    }
    
    private fun isFunctionCall(code: String, funcName: String): Boolean {
        return code.startsWith("$funcName ") || 
               code.startsWith("$funcName[") || 
               code.startsWith("$funcName$")
    }

    private fun parseListFunction(
        funcName: String,
        code: String,
        lineNum: Int,
        lineOffset: Int,
        combiner: (List<Pattern<TidalEvent>>) -> Pattern<TidalEvent>
    ): Pattern<TidalEvent> {
        // Handle optional $ (e.g. stack $ [...])
        val afterFunc = code.substringAfter(funcName).trimStart()
        val content = if (afterFunc.startsWith("$")) afterFunc.substringAfter("$").trimStart() else afterFunc
        
        // Find start of list
        val openIndex = code.indexOf('[', startIndex = funcName.length)
        if (openIndex == -1) throw IllegalArgumentException("Line $lineNum: $funcName requires [...]")
        
        val closeIndex = code.lastIndexOf(']')
        if (closeIndex == -1) throw IllegalArgumentException("Line $lineNum: $funcName missing closing ']'")
        
        val inner = code.substring(openIndex + 1, closeIndex)
        val innerOffset = lineOffset + openIndex + 1
        
        val parts = splitArgs(inner, innerOffset)
        val patterns = parts.mapNotNull { (partStr, partOffset) ->
            if (partStr.isBlank()) null
            else parsePattern(partStr, lineNum, partOffset)
        }
        
        return combiner(patterns)
    }

    private fun splitArgs(input: String, startOffset: Int): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var currentStart = 0
        var nesting = 0
        var insideQuote = false
        
        for (i in input.indices) {
            val c = input[i]
            when (c) {
                '"' -> insideQuote = !insideQuote
                '[', '(', '{' -> if (!insideQuote) nesting++
                ']', ')', '}' -> if (!insideQuote) nesting--
                ',' -> {
                    if (nesting == 0 && !insideQuote) {
                        result.add(input.substring(currentStart, i) to (startOffset + currentStart))
                        currentStart = i + 1
                    }
                }
            }
        }
        if (currentStart < input.length) {
            result.add(input.substring(currentStart) to (startOffset + currentStart))
        }
        return result
    }
    
    /**
     * Parse note names to MIDI note events with source locations.
     * Supports: c3, c#3, db4, etc. plus mini-notation modifiers like @2, *2, [], etc.
     * 
     * @param pattern The note pattern string (e.g., "c3 e3 g3" or "d2@2 [a2 g#2 g2]")
     * @param globalOffset Character offset of this pattern in the full code string
     */
    private fun parseNotePattern(pattern: String, globalOffset: Int = 0): Pattern<TidalEvent> {
        // Use TidalParser for proper mini-notation support (elongation, grouping, etc.)
        return when (val result = TidalParser.parseNotes(pattern)) {
            is TidalParser.ParseResult.Success -> {
                // Shift locations by globalOffset
                result.pattern.fmap { event ->
                    event.shiftLocations(globalOffset)
                }
            }
            is TidalParser.ParseResult.Failure -> {
                throw IllegalArgumentException(result.message)
            }
        }
    }
    
    /**
     * Parse voice indices to gate events with source locations.
     * 
     * @param pattern The voice pattern string (e.g., "0 1 2 3")
     * @param globalOffset Character offset of this pattern in the full code string
     */
    private fun parseVoicesPattern(pattern: String, globalOffset: Int = 0): Pattern<TidalEvent> {
        // Tweak: TidalParser counts from 0, so we need to offset the locations it returns.
        // But TidalParser.parseGates returns compiled pattern with relative locations.
        // We need to shift locations by globalOffset.
        // However, TidalEvent.withLocation is immutable, but we can map over it?
        // Or we rely on the visualizer being smart?
        // Let's implement location shifting.
        
        return when (val result = TidalParser.parseGates(pattern)) {
             is TidalParser.ParseResult.Success -> {
                 // Shift locations
                 result.pattern.fmap { event ->
                     event.shiftLocations(globalOffset)
                 }
             }
             is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
        }
    }
    
    /**
     * Parse hold values to VoiceHold events with source locations.
     * Creates a sequence of hold values paired with voice indices 0, 1, 2...
     * This enables: d1 $ voices "1 2 3" # hold "0.2 0.5 0"
     * 
     * @param pattern The hold pattern string (e.g., "0.2 0.5 0")
     * @param globalOffset Character offset of this pattern in the full code string
     */
    private fun parseHoldPattern(pattern: String, globalOffset: Int = 0): Pattern<TidalEvent> {
        // Parse the pattern string to get individual float tokens with their positions
        val tokens = pattern.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || (tokens.size == 1 && tokens[0].isBlank())) {
            return Pattern.silence()
        }
        
        // Create individual VoiceHold patterns with voice indices assigned at compile time
        var currentOffset = 0
        val holdPatterns = tokens.mapIndexedNotNull { index, token ->
            val floatVal = token.toFloatOrNull()
            if (floatVal == null) {
                currentOffset += token.length + 1 // +1 for whitespace
                return@mapIndexedNotNull null
            }
            
            val voiceIndex = index % 8 // Cycle through voices 0-7
            val location = SourceLocation(
                globalOffset + pattern.indexOf(token, currentOffset),
                globalOffset + pattern.indexOf(token, currentOffset) + token.length
            )
            currentOffset = pattern.indexOf(token, currentOffset) + token.length
            
            Pattern.pure<TidalEvent>(TidalEvent.VoiceHold(voiceIndex, floatVal.coerceIn(0f, 1f), listOf(location)))
        }
        
        return if (holdPatterns.isEmpty()) {
            Pattern.silence()
        } else if (holdPatterns.size == 1) {
            holdPatterns[0]
        } else {
            Pattern.fastcat(holdPatterns)
        }
    }
    
    /**
     * Parse sound names to gate events using default mapping.
     */
    /**
     * Parse sound names to sample events with source locations.
     */
    private fun parseSoundPattern(pattern: String, globalOffset: Int = 0): Pattern<TidalEvent> {
        return when (val result = TidalParser.parseSounds(pattern)) {
            is TidalParser.ParseResult.Success -> {
                // Shift locations
                result.pattern.fmap { event ->
                    event.shiftLocations(globalOffset)
                }
            }
            is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
        }
    }
    
    /**
     * Parse fast/slow transformations.
     */
    private fun parseTransformation(code: String, lineNum: Int, lineOffset: Int = 0): Pattern<TidalEvent> {
        val parts = code.split(Regex("\\s+"), limit = 3)
        if (parts.size < 3) {
            throw IllegalArgumentException("Line $lineNum: $parts[0] requires a number and pattern")
        }
        
        val op = parts[0]
        val factor = parts[1].toDoubleOrNull()
            ?: throw IllegalArgumentException("Line $lineNum: Invalid number '${parts[1]}'")
        
        // Calculate offset to the inner pattern (after "fast 2 " or "slow 2 ")
        var innerPatternStr = parts[2]
        var innerPatternOffset = lineOffset + code.indexOf(parts[2])
        
        // Handle $ function application (e.g., "slow 2 $ note \"c3\"")
        // The $ is just a separator in Tidal, similar to Haskell's function application
        if (innerPatternStr.startsWith("$ ")) {
            innerPatternStr = innerPatternStr.substringAfter("$ ")
            innerPatternOffset += 2 // Skip "$ "
        } else if (innerPatternStr.startsWith("$")) {
            innerPatternStr = innerPatternStr.substringAfter("$").trimStart()
            innerPatternOffset += 1 + (parts[2].substringAfter("$").length - innerPatternStr.length)
        }
        
        val innerPattern = parsePattern(innerPatternStr, lineNum, innerPatternOffset)
        
        return when (op) {
            "fast" -> innerPattern.fast(factor)
            "slow" -> innerPattern.slow(factor)
            else -> throw IllegalArgumentException("Line $lineNum: Unknown transformation '$op'")
        }
    }
    
    /**
     * Update the scheduler with the combined active pattern.
     */
    private fun updateScheduler() {
        val activePatterns = slots.entries
            .filter { (slot, _) -> 
                !mutedSlots.contains(slot) && 
                (soloSlot == null || soloSlot == slot)
            }
            .map { it.value }
        
        if (activePatterns.isEmpty()) {
            scheduler.stop()
        } else {
            scheduler.setPattern(Pattern.stack(activePatterns))
            if (!scheduler.state.value.isPlaying) {
                scheduler.play()
            }
        }
    }
    
    /**
     * Silence all patterns.
     */
    fun hush() {
        slots.clear()
        mutedSlots.clear()
        soloSlot = null
        scheduler.stop()
        _activeSlots.value = emptySet()
        logConsole(ConsoleType.INFO, "All patterns silenced")
    }
    
    /**
     * Solo a slot (mute all others).
     */
    fun solo(slot: String) {
        soloSlot = if (soloSlot == slot) null else slot
        updateScheduler()
        logConsole(ConsoleType.INFO, if (soloSlot != null) "Solo: $slot" else "Solo off")
    }
    
    /**
     * Mute a slot.
     */
    fun mute(slot: String) {
        mutedSlots.add(slot)
        updateScheduler()
        logConsole(ConsoleType.INFO, "Muted: $slot")
    }
    
    /**
     * Unmute a slot.
     */
    fun unmute(slot: String) {
        mutedSlots.remove(slot)
        updateScheduler()
        logConsole(ConsoleType.INFO, "Unmuted: $slot")
    }
    
    /**
     * Undo last evaluation.
     */
    fun undo() {
        if (history.isEmpty()) {
            logConsole(ConsoleType.ERROR, "Nothing to undo")
            return
        }
        
        val snapshot = history.removeLast()
        slots.clear()
        slots.putAll(snapshot.slots)
        updateScheduler()
        _activeSlots.value = slots.keys.toSet()
        logConsole(ConsoleType.INFO, "Undone to previous state")
    }
    
    /**
     * Save current state for undo.
     */
    private fun saveSnapshot() {
        history.add(ReplSnapshot(slots.toMap(), currentTimeMillis()))
        if (history.size > maxHistorySize) {
            history.removeFirst()
        }
    }
    
    /**
     * Log to console.
     */
    private fun logConsole(type: ConsoleType, message: String, snippet: String? = null) {
        val entry = ConsoleEntry(currentTimeMillis(), type, message, snippet)
        _console.value = (_console.value + entry).takeLast(50)
    }
    
    /**
     * Clear console.
     */
    fun clearConsole() {
        _console.value = emptyList()
    }
}
