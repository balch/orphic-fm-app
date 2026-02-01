package org.balch.orpheus.features.tidal

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.core.tidal.ConsoleEntry
import org.balch.orpheus.core.tidal.EvalMode
import org.balch.orpheus.core.tidal.Pattern
import org.balch.orpheus.core.tidal.ReplCodeEvent
import org.balch.orpheus.core.tidal.ReplCodeEventBus
import org.balch.orpheus.core.tidal.ReplResult
import org.balch.orpheus.core.tidal.TidalEvent
import org.balch.orpheus.core.tidal.TidalParser
import org.balch.orpheus.core.tidal.TidalRepl
import org.balch.orpheus.core.tidal.TidalScheduler
import org.balch.orpheus.core.tidal.TidalSchedulerState


/**
 * UI state for the Live Code editor.
 */
data class LiveCodeUiState(
    val code: TextFieldValue = TextFieldValue(requireNotNull(LiveCodeViewModel.EXAMPLES["simple"])),
    val selectedExample: String? = "simple",
    val isPlaying: Boolean = false,
    val currentCycle: Int = 0,
    val cyclePosition: Double = 0.0,
    val bpm: Double = 120.0,
    val replVolume: Float = 0.8f,  // REPL voice volume (Quad 2, voices 8-11) - default slightly boosted
    val error: String? = null,
    val history: List<String> = emptyList(),
    val activeSlots: Set<String> = emptySet(),
    val isAiGenerating: Boolean = false
)

data class LiveCodePanelActions(
    val onCodeChange: (TextFieldValue) -> Unit,
    val onExecuteBlock: () -> Unit,
    val onExecuteLine: () -> Unit,
    val onBpmChange: (Double) -> Unit,
    val onReplVolumeChange: (Float) -> Unit,
    val onExecute: () -> Unit,
    val onStop: () -> Unit,
    val onLoadExample: (String) -> Unit,
    val onDeleteLine: () -> Unit
) {
    companion object {
        val EMPTY = LiveCodePanelActions(
            onCodeChange = { },
            onExecuteBlock = { },
            onExecuteLine = { },
            onBpmChange = { },
            onReplVolumeChange = { },
            onExecute = { },
            onStop = { },
            onLoadExample = { },
            onDeleteLine = { })
    }
}

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
    data class SetReplVolume(val volume: Float) : LiveCodeIntent()
    data class LoadExample(val name: String) : LiveCodeIntent()
    data class HandleAiCode(val code: String, val slots: List<String>) : LiveCodeIntent()
    data object DeleteLine : LiveCodeIntent()
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
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LiveCodeViewModel(
    private val scheduler: TidalScheduler,
    private val repl: TidalRepl,
    private val replCodeEventBus: ReplCodeEventBus,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val synthEngine: SynthEngine
) : ViewModel(), LiveCodeFeature {

    override val actions = LiveCodePanelActions(
        onCodeChange = ::updateCode,
        onExecuteBlock = ::executeBlock,
        onExecuteLine = ::executeLine,
        onBpmChange = ::setBpm,
        onReplVolumeChange = ::setReplVolume,
        onExecute = ::execute,
        onStop = ::stop,
        onLoadExample = ::loadExample,
        onDeleteLine = ::deleteLine
    )
    
    private val logger = logging("LiveCodeViewModel")
    
    private val _intents = MutableStateFlow<LiveCodeIntent?>(null)
    
    private val _uiState = MutableStateFlow(LiveCodeUiState())
    override val stateFlow: StateFlow<LiveCodeUiState> = _uiState.asStateFlow()
    
    // Expose scheduler state for cycle position display
    val schedulerState: StateFlow<TidalSchedulerState> = scheduler.state
    
    // Expose console output from REPL
    val console: StateFlow<List<ConsoleEntry>> = repl.console
    
    // Expose trigger events for UI highlighting (voice indices when triggered)
    override val triggers = scheduler.triggers
    
    init {
        logger.d { "LiveCodeViewModel initialized, subscribing to ReplCodeEventBus" }
        
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
                val wasPlaying = _uiState.value.isPlaying
                _uiState.value = _uiState.value.copy(
                    isPlaying = schedState.isPlaying,
                    currentCycle = schedState.currentCycle,
                    cyclePosition = schedState.cyclePosition,
                    bpm = schedState.bpm
                )
                
                // Notify MediaSessionStateManager when REPL playing state changes
                if (wasPlaying != schedState.isPlaying) {
                    mediaSessionStateManager.setReplPlaying(schedState.isPlaying)
                }
            }
        }
        
        // Subscribe to AI generated code events
        viewModelScope.launch {
            logger.d { "Starting ReplCodeEventBus subscription..." }
            replCodeEventBus.events.collect { event ->
                logger.d { "Received ReplCodeEvent: $event" }
                when (event) {
                    is ReplCodeEvent.Generated -> {
                        logger.d { "AI generated code received, updating editor: ${event.code.take(50)}..." }
                        _intents.value = LiveCodeIntent.HandleAiCode(event.code, event.slots)
                    }
                    is ReplCodeEvent.Generating -> {
                        logger.d { "AI is generating code..." }
                        // Set loading state
                        _uiState.value = _uiState.value.copy(isAiGenerating = true)
                    }
                    is ReplCodeEvent.Failed -> {
                        logger.w { "AI code generation failed: ${event.error}" }
                        // Clear generating state on failure so UI is not blocked
                        _uiState.value = _uiState.value.copy(
                            isAiGenerating = false,
                            error = event.error
                        )
                    }
                    is ReplCodeEvent.UserInteraction -> {
                        logger.d { "User took manual control" }
                        // Clear loading state when user takes control
                        _uiState.value = _uiState.value.copy(isAiGenerating = false)
                    }
                }
            }
        }
        
        // Subscribe to playback lifecycle events (e.g., foreground service stop)
        viewModelScope.launch {
            playbackLifecycleManager.events.collect { event ->
                when (event) {
                    is PlaybackLifecycleEvent.StopAll -> {
                        logger.debug { "Received StopAll event - stopping REPL" }
                        repl.hush()
                        _uiState.value = _uiState.value.copy(
                            isPlaying = false, 
                            activeSlots = emptySet(),
                            isAiGenerating = false
                        )
                    }
                    else -> { /* Ignore other events */ }
                }
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
            
            is LiveCodeIntent.HandleAiCode -> {
                logger.d { "HandleAiCode: updating code to ${intent.code.take(50)}..." }
                // Update code and set playing/slots state
                // Set selectedExample to "AI" so dropdown shows "AI" instead of "Examples"
                state.copy(
                    code = TextFieldValue(intent.code),
                    selectedExample = "AI",
                    error = null,
                    isPlaying = if (intent.code.equals("hush", true)) false else true,
                    activeSlots = intent.slots.toSet(),
                    history = (listOf(intent.code) + state.history).take(10),
                    isAiGenerating = false
                )
            }

            is LiveCodeIntent.Execute -> {
                // Execute full code - Replace mode ensures deleted lines stop playing
                executeCode(state.code.text, state, EvalMode.REPLACE)
            }
            
            is LiveCodeIntent.ExecuteBlock -> {
                // Execute block - Merge mode modifies only the active block without stopping others
                val block = findBlock(state.code)
                executeCode(block, state, EvalMode.MERGE)
            }
            
            is LiveCodeIntent.ExecuteLine -> {
                // Execute line - Merge mode modifies only the active line
                val line = findLine(state.code)
                executeCode(line, state, EvalMode.MERGE)
            }

            is LiveCodeIntent.DeleteLine -> {
                deleteCurrentLine(state)
            }
            
            is LiveCodeIntent.Play -> {
                // If already playing, do nothing
                // If stopped, re-execute the code to reapply all parameters
                if (!state.isPlaying && state.code.text.isNotBlank()) {
                    logger.debug { "Re-executing code after stop to restore parameters" }
                    executeCode(state.code.text, state, EvalMode.REPLACE)
                } else {
                    scheduler.play()
                }
                state.copy(isPlaying = true, isAiGenerating = false)
            }
            
            is LiveCodeIntent.Stop -> {
                // Hush all patterns and stop the scheduler for a clean stop
                // Also clear isAiGenerating so user can edit the code after stopping
                repl.hush()
                state.copy(isPlaying = false, activeSlots = emptySet(), isAiGenerating = false)
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
            
            is LiveCodeIntent.SetReplVolume -> {
                // Apply volume to Quad 2 (voices 8-11) - the REPL voices
                synthEngine.setQuadVolume(2, intent.volume)
                state.copy(replVolume = intent.volume)
            }
        }
    }
    
    /**
     * Execute a chunk of code (block, line, or full text).
     * Delegates to TidalRepl for evaluation and pattern management.
     */
    private fun executeCode(
        code: String, 
        state: LiveCodeUiState, 
        mode: EvalMode = EvalMode.REPLACE
    ): LiveCodeUiState {
        if (code.isBlank()) return state.copy(error = "No code selected")
        
        repl.evaluate(code, mode) { result ->
            when (result) {
                is ReplResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        error = null,
                        isPlaying = true,
                        activeSlots = result.slots,
                        history = (listOf(code) + _uiState.value.history).take(10)
                    )
                }
                is ReplResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
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

    private fun deleteCurrentLine(state: LiveCodeUiState): LiveCodeUiState {
        val text = state.code.text
        if (text.isEmpty()) return state
        
        val cursor = state.code.selection.start.coerceIn(0, text.length)
        // Find line bounds
        val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
        val end = text.indexOf('\n', cursor)
        
        var deleteStart = start
        var deleteEnd = if (end == -1) text.length else end + 1
        
        // If it's the last line (no newline at end), and there's a previous line, delete the preceding newline
        if (end == -1 && start > 0) {
             deleteStart = start - 1 
        }
        
        val newText = text.removeRange(deleteStart, deleteEnd)
        val newCursor = deleteStart.coerceAtMost(newText.length)
        
        return state.copy(
            code = TextFieldValue(newText, TextRange(newCursor))
        )
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
                when (val result = TidalParser.parseGates(line)) {
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
        viewModelScope.launch {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.Execute
        }
    }
    
    fun executeBlock() {
        viewModelScope.launch {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.ExecuteBlock
        }
    }
    
    fun executeLine() {
        viewModelScope.launch {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.ExecuteLine
        }
    }
    
    fun play() {
        _intents.value = LiveCodeIntent.Play
    }
    
    fun stop() {
        viewModelScope.launch {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.Stop
        }
    }
    
    fun setBpm(bpm: Double) {
        _intents.value = LiveCodeIntent.SetBpm(bpm)
    }
    
    fun setReplVolume(volume: Float) {
        _intents.value = LiveCodeIntent.SetReplVolume(volume)
    }
    
    fun loadExample(name: String) {
        _intents.value = LiveCodeIntent.LoadExample(name)
    }

    fun deleteLine() {
        _intents.value = LiveCodeIntent.DeleteLine
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
            "simple" to """
d1 $ voices:1 2 3 4
            """.trimIndent(),
            
            "gadda" to """
bpm 108

# Add some organ-like sustain
drive:0.55
distmix:0.55
feedback:0.3
delaymix:0.15

d1 $ note "d3 d3 d3 d3 a3 g#3 g3 f#3"
d2 $ note "d2@4 [a2 g#2 g2 f#2]"
d3 $ note "d4 ~ d4 ~ [a4 g#4 g4 f#4] ~"
d4 $ slow 2 $ note "d2 d2 d2 d2 [a2 g#2 g2 f#2] d2 d2 d2"
            """.trimIndent(),
            
            "euclidean" to """
d1 $ note "c3(3,8) e3(5,8) g3(3,8)"
d2 $ voices:1(3,8) 2(5,8) 3(3,8) 4(5,8)
            """.trimIndent(),
            
            "layered" to """
d1 $ slow 2 note "<c3 e3> <g3 c4>"
d2 $ fast 2 voices:5 6 7 8
d3 $ quadhold:1 0.8
d4 $ quadpitch:1 0.3
            """.trimIndent(),
            
            "evolving" to """
d1 $ note "[c2 e2 g2]*2"
d2 $ slow 4 voices:<1 2> <3 4> <5 6>
            """.trimIndent()
        )

        fun previewFeature(state: LiveCodeUiState = LiveCodeUiState()) =
            object : LiveCodeFeature {
                override val stateFlow: StateFlow<LiveCodeUiState> = MutableStateFlow(state)
                override val actions: LiveCodePanelActions = LiveCodePanelActions.EMPTY
                override val triggers: Flow<TidalScheduler.TriggerEvent> = emptyFlow()
            }

        @Composable
        fun feature(): LiveCodeFeature =
             synthViewModel<LiveCodeViewModel, LiveCodeUiState, LiveCodePanelActions>() as LiveCodeFeature
    }
}
