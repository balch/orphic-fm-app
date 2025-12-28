package org.balch.orpheus.features.tidal

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import org.balch.orpheus.core.tidal.ConsoleEntry
import org.balch.orpheus.core.tidal.Pattern
import org.balch.orpheus.core.tidal.TidalEvent
import org.balch.orpheus.core.tidal.TidalParser
import org.balch.orpheus.core.tidal.TidalRepl
import org.balch.orpheus.core.tidal.TidalScheduler
import org.balch.orpheus.core.tidal.TidalSchedulerState

/**
 * UI state for the Live Code editor.
 */
data class LiveCodeUiState(
    val code: TextFieldValue = TextFieldValue(requireNotNull(LiveCodeViewModel.EXAMPLES["repl"])),
    val selectedExample: String? = "repl",
    val isPlaying: Boolean = false,
    val currentCycle: Int = 0,
    val cyclePosition: Double = 0.0,
    val bpm: Double = 120.0,
    val error: String? = null,
    val history: List<String> = emptyList(),
    val activeSlots: Set<String> = emptySet()
)

/**
 * User intents for the Live Code editor.
 */
sealed class LiveCodeIntent {
    data class UpdateCode(val code: TextFieldValue) : LiveCodeIntent()
    data object Play : LiveCodeIntent()
    data object Stop : LiveCodeIntent()
    data object Execute : LiveCodeIntent()
    data object ExecuteBlock : LiveCodeIntent()
    data object ExecuteLine : LiveCodeIntent()
    data class SetBpm(val bpm: Double) : LiveCodeIntent()
    data class LoadExample(val name: String) : LiveCodeIntent()
}

/**
 * ViewModel for the Live Code editor.
 * 
 * Manages code editing, parsing, playback, and history.
 * Uses MVI pattern for state management.
 * Delegates to TidalRepl for REPL evaluation lifecycle.
 */
@Inject
@ViewModelKey(LiveCodeViewModel::class)
@ContributesIntoMap(AppScope::class)
class LiveCodeViewModel(
    private val scheduler: TidalScheduler,
    private val repl: TidalRepl
) : ViewModel() {
    
    private val _intents = MutableStateFlow<LiveCodeIntent?>(null)
    
    private val _uiState = MutableStateFlow(LiveCodeUiState())
    val uiState: StateFlow<LiveCodeUiState> = _uiState.asStateFlow()
    
    // Expose scheduler state for cycle position display
    val schedulerState: StateFlow<TidalSchedulerState> = scheduler.state
    
    // Expose console output from REPL
    val console: StateFlow<List<ConsoleEntry>> = repl.console
    
    // Expose trigger events for UI highlighting (voice indices when triggered)
    val triggers = scheduler.triggers
    
    init {
        // Subscribe to intents using scan pattern
        viewModelScope.launch {
            _intents
                .scan(_uiState.value) { state, intent ->
                    intent?.let { reduce(state, it) } ?: state
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
        
        // Subscribe to scheduler state updates
        viewModelScope.launch {
            scheduler.state.collect { schedState ->
                _uiState.value = _uiState.value.copy(
                    isPlaying = schedState.isPlaying,
                    currentCycle = schedState.currentCycle,
                    cyclePosition = schedState.cyclePosition,
                    bpm = schedState.bpm
                )
            }
        }
    }
    
    /**
     * Reduce state from intent.
     */
    private fun reduce(state: LiveCodeUiState, intent: LiveCodeIntent): LiveCodeUiState {
        return when (intent) {
            is LiveCodeIntent.UpdateCode -> {
                state.copy(code = intent.code, error = null)
            }
            
            is LiveCodeIntent.Execute -> {
                executeCode(state.code.text, state)
            }
            
            is LiveCodeIntent.ExecuteBlock -> {
                val block = findBlock(state.code)
                executeCode(block, state)
            }
            
            is LiveCodeIntent.ExecuteLine -> {
                val line = findLine(state.code)
                executeCode(line, state)
            }
            
            is LiveCodeIntent.Play -> {
                scheduler.play()
                state.copy(isPlaying = true)
            }
            
            is LiveCodeIntent.Stop -> {
                scheduler.stop()
                state.copy(isPlaying = false)
            }
            
            is LiveCodeIntent.SetBpm -> {
                scheduler.setBpm(intent.bpm)
                state.copy(bpm = intent.bpm)
            }
            
            is LiveCodeIntent.LoadExample -> {
                val example = EXAMPLES[intent.name] ?: return state
                // Reset REPL state when changing examples to stop old patterns
                repl.hush()
                state.copy(
                    code = TextFieldValue(example), 
                    selectedExample = intent.name, 
                    error = null,
                    isPlaying = false,
                    activeSlots = emptySet()
                )
            }
        }
    }
    
    /**
     * Execute a chunk of code (block, line, or full text).
     * Delegates to TidalRepl for evaluation and pattern management.
     */
    private fun executeCode(code: String, state: LiveCodeUiState): LiveCodeUiState {
        if (code.isBlank()) return state.copy(error = "No code selected")
        
        var resultState = state
        
        repl.evaluate(code) { result ->
            when (result) {
                is org.balch.orpheus.core.tidal.ReplResult.Success -> {
                    viewModelScope.launch {
                        _uiState.value = _uiState.value.copy(
                            error = null,
                            isPlaying = true,
                            activeSlots = result.slots,
                            history = (listOf(code) + _uiState.value.history).take(10)
                        )
                    }
                }
                is org.balch.orpheus.core.tidal.ReplResult.Error -> {
                    viewModelScope.launch {
                        _uiState.value = _uiState.value.copy(error = result.message)
                    }
                }
            }
        }
        
        // Return current state - actual update happens asynchronously via callback
        return state
    }

    private fun findBlock(value: TextFieldValue): String {
        val text = value.text
        if (text.isEmpty()) return ""
        
        val cursor = value.selection.start.coerceIn(0, text.length)
        // Find start of block (previous empty line)
        var start = text.lastIndexOf("\n\n", (cursor - 1).coerceAtLeast(0))
        if (start == -1) start = 0 else start += 2 // skip \n\n
        
        // Find end of block (next empty line)
        var end = text.indexOf("\n\n", cursor)
        if (end == -1) end = text.length
        
        return text.substring(start, end).trim()
    }
    
    private fun findLine(value: TextFieldValue): String {
        val text = value.text
        if (text.isEmpty()) return ""
        
        val cursor = value.selection.start.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
        val end = text.indexOf('\n', cursor).takeIf { it != -1 } ?: text.length
        
        return text.substring(start, end).trim()
    }
    
    /**
     * Parse a single line of pattern code.
     */
    private fun parseLine(line: String): Pattern<TidalEvent>? {
        // Skip comments
        if (line.startsWith("#") || line.startsWith("//")) {
            return null
        }
        
        // Check for prefixes
        return when {
            line.startsWith("gates:") -> {
                val gateStr = line.substringAfter("gates:").trim()
                val result = TidalParser.parseGates(gateStr)
                when (result) {
                    is TidalParser.ParseResult.Success -> result.pattern
                    is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
                }
            }
            
            line.startsWith("tune:") -> {
                // Format: "tune:0 0.2 0.4 0.6" for voice 0
                val parts = line.substringAfter("tune:").trim().split(" ", limit = 2)
                if (parts.size < 2) throw IllegalArgumentException("tune requires voice index and values")
                val voiceIndex = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid voice index")
                val values = parts[1]
                val result = TidalParser.parseFloats(values) { TidalEvent.VoiceTune(voiceIndex, it) }
                when (result) {
                    is TidalParser.ParseResult.Success -> result.pattern
                    is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
                }
            }
            
            line.startsWith("delay:") -> {
                // Format: "delay:0 0.2 0.4 0.6" for delay 0
                val parts = line.substringAfter("delay:").trim().split(" ", limit = 2)
                if (parts.size < 2) throw IllegalArgumentException("delay requires index and values")
                val delayIndex = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid delay index")
                val values = parts[1]
                val result = TidalParser.parseFloats(values) { TidalEvent.DelayTime(delayIndex, it) }
                when (result) {
                    is TidalParser.ParseResult.Success -> result.pattern
                    is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
                }
            }
            
            line.startsWith("lfo:") -> {
                // Format: "lfo:0 0.5 1.0 2.0" for LFO 0 frequency sequence
                val parts = line.substringAfter("lfo:").trim().split(" ", limit = 2)
                if (parts.size < 2) throw IllegalArgumentException("lfo requires index and values")
                val lfoIndex = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid LFO index")
                val values = parts[1]
                val result = TidalParser.parseFloats(values) { TidalEvent.LfoFreq(lfoIndex, it) }
                when (result) {
                    is TidalParser.ParseResult.Success -> result.pattern
                    is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
                }
            }
            
            // Check for sound pattern: "s 'bd sn'"
            line.startsWith("s ") || line.startsWith("sound ") -> {
                val prefix = if (line.startsWith("s ")) "s " else "sound "
                val soundStr = line.substringAfter(prefix).trim()
                val result = TidalParser.parseSounds(soundStr)
                when (result) {
                    is TidalParser.ParseResult.Success -> result.pattern
                    is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
                }
            }

            // Check for transformations: "fast N pattern" or "slow N pattern"
            line.startsWith("fast ") -> {
                val parts = line.substringAfter("fast ").trim().split(" ", limit = 2)
                val factor = parts[0].toDoubleOrNull() ?: throw IllegalArgumentException("fast requires a number")
                if (parts.size < 2) throw IllegalArgumentException("fast requires a pattern")
                val inner = parseLine(parts[1]) ?: throw IllegalArgumentException("Invalid inner pattern")
                inner.fast(factor)
            }
            
            line.startsWith("slow ") -> {
                val parts = line.substringAfter("slow ").trim().split(" ", limit = 2)
                val factor = parts[0].toDoubleOrNull() ?: throw IllegalArgumentException("slow requires a number")
                if (parts.size < 2) throw IllegalArgumentException("slow requires a pattern")
                val inner = parseLine(parts[1]) ?: throw IllegalArgumentException("Invalid inner pattern")
                inner.slow(factor)
            }
            
            // Default: treat as gate pattern (just numbers)
            else -> {
                val result = TidalParser.parseGates(line)
                when (result) {
                    is TidalParser.ParseResult.Success -> result.pattern
                    is TidalParser.ParseResult.Failure -> throw IllegalArgumentException(result.message)
                }
            }
        }
    }
    
    // === Public Actions ===
    
    fun updateCode(code: TextFieldValue) {
        _intents.value = LiveCodeIntent.UpdateCode(code)
    }
    
    fun execute() {
        _intents.value = LiveCodeIntent.Execute
    }
    
    fun executeBlock() {
        _intents.value = LiveCodeIntent.ExecuteBlock
    }
    
    fun executeLine() {
        _intents.value = LiveCodeIntent.ExecuteLine
    }
    
    fun play() {
        _intents.value = LiveCodeIntent.Play
    }
    
    fun stop() {
        _intents.value = LiveCodeIntent.Stop
    }
    
    fun setBpm(bpm: Double) {
        _intents.value = LiveCodeIntent.SetBpm(bpm)
    }
    
    fun loadExample(name: String) {
        _intents.value = LiveCodeIntent.LoadExample(name)
    }
    
    /**
     * Silence all patterns (delegated to REPL).
     */
    fun hush() {
        repl.hush()
        _uiState.value = _uiState.value.copy(isPlaying = false, activeSlots = emptySet())
    }
    
    override fun onCleared() {
        super.onCleared()
        scheduler.stop()
    }
    
    companion object {
        /**
         * Example patterns for users to learn from.
         */
        val EXAMPLES = mapOf(
            "gadda" to """
# In-A-Gadda-Da-Vida bass riff (~119 BPM, D minor)
# D-D (8th 8th) F-E (8th 8th) C (quarter) D (8th) A-Ab-G (triplet @2) ~ (rest)
d1 $ note "d2 d2 f2 e2 c2@2 d2 [a2 g#2 g2]@2"
            """.trimIndent(),
            
            "repl" to """
# REPL pattern slots (d1-d8)
d1 $ voices:0 1 2 3
d2 $ fast 2 voices:4 5

# Use 'hush' to silence all
            """.trimIndent(),
            
            "notes" to """
# Play melodic notes (c4 = middle C)
d1 $ note "c3 e3 g3 c4"

# Try sharps and flats
d2 $ note "c#3 d#3 f#3"
            """.trimIndent(),
            
            "drums" to """
# Drum sounds → voice indices
# bd=0, sn=1, hh=2, cp=3
d1 $ s "bd sn bd sn"
d2 $ fast 2 s "hh hh hh hh"
            """.trimIndent(),
            
            "drone" to """
# Evolving Drone: layered voices
slow 2 voices:<0 1> <2 3>
tune:0 0.25 0.28 0.32 0.25
tune:1 0.42 0.45 0.48 0.45
            """.trimIndent(),
            
            "simple" to """
# Simple voice pattern
voices:0 1 2 3
            """.trimIndent(),
            
            "polyrhythm" to """
# Polyrhythm - different speeds
d1 $ voices:0 1 2 3
d2 $ fast 2 voices:4 5 6 7
            """.trimIndent()
        )
    }
}
