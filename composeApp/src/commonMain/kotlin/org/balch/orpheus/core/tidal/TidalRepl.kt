package org.balch.orpheus.core.tidal

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * REPL orchestrator for Tidal live coding.
 * 
 * Manages pattern slots (d1-d16), evaluation lifecycle, and console output.
 * All heavy work is dispatched to a background thread via coroutines.
 * 
 * TODO: Future enhancements:
 * - Euclidean rhythms (bjorklund algorithm)
 * - Chord notation (c'maj, c'min7)
 * - Control parameters DSL (gain, cutoff, pan)
 * - Sample player plugin integration
 */
@SingleIn(AppScope::class)
@Inject
class TidalRepl(
    private val scheduler: TidalScheduler
) {
    // Use Default dispatcher for CPU-bound parsing work
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
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
     */
    fun evaluate(code: String, onResult: (ReplResult) -> Unit = {}) {
        scope.launch {
            val result = evaluateInternal(code)
            onResult(result)
        }
    }
    
    /**
     * Evaluate code synchronously (for internal use).
     */
    private fun evaluateInternal(code: String): ReplResult {
        if (code.isBlank()) {
            logConsole(ConsoleType.ERROR, "No code to evaluate")
            return ReplResult.Error("No code to evaluate")
        }
        
        // Save snapshot for undo
        saveSnapshot()
        
        return try {
            // Use original code without trim to preserve character offsets matching the UI
            val modifiedSlots = mutableSetOf<String>()
            
            // Track character offset as we process each line
            var currentOffset = 0
            
            for ((lineNum, line) in code.lines().withIndex()) {
                val lineOffset = currentOffset
                currentOffset += line.length + 1 // +1 for newline
                
                val trimmed = line.trim()
                
                // Skip blank lines and comments
                if (trimmed.isBlank() || trimmed.startsWith("//") || trimmed.startsWith("#")) continue
                
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
                    // Anonymous pattern - goes to d0
                    val patternOffset = lineOffset + line.indexOf(trimmed)
                    val pattern = parsePattern(trimmed, lineNum + 1, patternOffset)
                    slots["d0"] = pattern
                    modifiedSlots.add("d0")
                }
            }
            
            // Update scheduler with combined pattern
            updateScheduler()
            
            _activeSlots.value = slots.keys.toSet()
            
            val msg = "Evaluated: ${modifiedSlots.joinToString(", ")}"
            logConsole(ConsoleType.SUCCESS, msg, code.take(50))
            ReplResult.Success(modifiedSlots, msg)
            
        } catch (e: Exception) {
            val msg = e.message ?: "Parse error"
            logConsole(ConsoleType.ERROR, msg, code.take(50))
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
        
        // Check for note patterns: note "c3 e3 g3" or note("c3 e3 g3")
        val noteMatch = Regex("^note\\s*[\"(](.+)[\"')]$").find(trimmed)
        if (noteMatch != null) {
            val notePattern = noteMatch.groupValues[1]
            // Calculate offset: trimOffset + position of quote content in the match
            val quoteContentOffset = trimOffset + trimmed.indexOf(notePattern)
            return parseNotePattern(notePattern, quoteContentOffset)
        }
        
        // Check for sound patterns: s "bd sn" or sound "bd sn"
        val soundMatch = Regex("^(?:s|sound)\\s*[\"(](.+)[\"')]$").find(trimmed)
        if (soundMatch != null) {
            val soundPattern = soundMatch.groupValues[1]
            val quoteContentOffset = trimOffset + trimmed.indexOf(soundPattern)
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
        
        // Check for transformations
        if (trimmed.startsWith("fast ") || trimmed.startsWith("slow ")) {
            return parseTransformation(trimmed, lineNum, trimOffset)
        }
        
        // Default: try as gate pattern (just numbers)
        return when (val result = TidalParser.parseGates(trimmed)) {
            is TidalParser.ParseResult.Success -> result.pattern
            is TidalParser.ParseResult.Failure -> throw IllegalArgumentException("Line $lineNum: ${result.message}")
        }
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
        val innerPatternOffset = lineOffset + code.indexOf(parts[2])
        val innerPattern = parsePattern(parts[2], lineNum, innerPatternOffset)
        
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
