package org.balch.orpheus.features.tidal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.SynthFeature
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
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
@Immutable
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

@Immutable
data class LiveCodePanelActions(
    val setCode: (TextFieldValue) -> Unit,
    val executeBlock: () -> Unit,
    val executeLine: () -> Unit,
    val setBpm: (Double) -> Unit,
    val setReplVolume: (Float) -> Unit,
    val execute: () -> Unit,
    val stop: () -> Unit,
    val loadExample: (String) -> Unit,
    val deleteLine: () -> Unit
) {
    companion object {
        val EMPTY = LiveCodePanelActions(
            setCode = { },
            executeBlock = { },
            executeLine = { },
            setBpm = { },
            setReplVolume = { },
            execute = { },
            stop = { },
            loadExample = { },
            deleteLine = { })
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
@ContributesIntoSet(AppScope::class, binding = binding<SynthFeature<*, *>>())
class LiveCodeViewModel(
    private val scheduler: TidalScheduler,
    private val repl: TidalRepl,
    private val replCodeEventBus: ReplCodeEventBus,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val synthEngine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel(), LiveCodeFeature {

    override val actions = LiveCodePanelActions(
        setCode = ::updateCode,
        executeBlock = ::executeBlock,
        executeLine = ::executeLine,
        setBpm = ::setBpm,
        setReplVolume = ::setReplVolume,
        execute = ::execute,
        stop = ::stop,
        loadExample = ::loadExample,
        deleteLine = ::deleteLine
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
        logger.d { "LiveCodeViewModel initialized" }
        observeIntents()
        observeSchedulerState()
        observeReplCodeEvents()
        observePlaybackLifecycle()
    }

    private fun observeIntents() {
        viewModelScope.launch(dispatcherProvider.default) {
            _intents
                .scan(_uiState.value) { state, intent ->
                    intent?.let { reduce(state, it) } ?: state
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
    }

    private fun observeSchedulerState() {
        viewModelScope.launch(dispatcherProvider.default) {
            scheduler.state.collect { schedState ->
                val wasPlaying = _uiState.value.isPlaying
                _uiState.value = _uiState.value.copy(
                    isPlaying = schedState.isPlaying,
                    currentCycle = schedState.currentCycle,
                    cyclePosition = schedState.cyclePosition,
                    bpm = schedState.bpm
                )
                if (wasPlaying != schedState.isPlaying) {
                    mediaSessionStateManager.setReplPlaying(schedState.isPlaying)
                }
            }
        }
    }

    private fun observeReplCodeEvents() {
        viewModelScope.launch(dispatcherProvider.default) {
            replCodeEventBus.events.collect { event ->
                logger.d { "Received ReplCodeEvent: $event" }
                when (event) {
                    is ReplCodeEvent.Generated -> {
                        logger.d { "AI generated code received, updating editor: ${event.code.take(50)}..." }
                        _intents.value = LiveCodeIntent.HandleAiCode(event.code, event.slots)
                    }
                    is ReplCodeEvent.Generating -> {
                        logger.d { "AI is generating code..." }
                        _uiState.value = _uiState.value.copy(isAiGenerating = true)
                    }
                    is ReplCodeEvent.Failed -> {
                        logger.w { "AI code generation failed: ${event.error}" }
                        _uiState.value = _uiState.value.copy(
                            isAiGenerating = false,
                            error = event.error
                        )
                    }
                    is ReplCodeEvent.UserInteraction -> {
                        logger.d { "User took manual control" }
                        _uiState.value = _uiState.value.copy(isAiGenerating = false)
                    }
                }
            }
        }
    }

    private fun observePlaybackLifecycle() {
        viewModelScope.launch(dispatcherProvider.default) {
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
        viewModelScope.launch(dispatcherProvider.default) {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.Execute
        }
    }

    fun executeBlock() {
        viewModelScope.launch(dispatcherProvider.default) {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.ExecuteBlock
        }
    }

    fun executeLine() {
        viewModelScope.launch(dispatcherProvider.default) {
            replCodeEventBus.emitUserInteraction()
            _intents.value = LiveCodeIntent.ExecuteLine
        }
    }
    
    fun play() {
        _intents.value = LiveCodeIntent.Play
    }
    
    fun stop() {
        viewModelScope.launch(dispatcherProvider.default) {
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
d1 $ note "c3 e3 g3 c4"
d2 $ voices:1 2 3 4
            """.trimIndent(),

            "gadda" to """
bpm 108

# In-A-Gadda-Da-Vida
drive:0.6
distmix:0.6
feedback:0.35
delaymix:0.2

# VA organ pad on pair 1 (voices 1-2)
engine:1 va
sharp:1 0.7
tune:1 0.354
tune:2 0.500
envspeed:1 0.85
envspeed:2 0.85

# String bass drone on pair 2 (voices 3-4)
engine:2 string
tune:3 0.354
tune:4 0.354
envspeed:3 0.8
envspeed:4 0.8

# FM shimmer on pair 3 (voices 5-6)
engine:3 fm
tune:5 0.604
tune:6 0.500
envspeed:5 0.7
envspeed:6 0.7

# Drums - rock groove
d1 $ s "<[bd ~ sn ~] [bd ~ sn ~] [bd ~ sn ~] [bd ~ sn [sn sn]]>"
d2 $ s "hh hh <hh oh> hh"

# Organ riff - the iconic melody
d3 $ note "d3 d3 d3 d3 a3 g#3 g3 f#3"

# Bass line
d4 $ note "d2@4 [a2 g#2 g2 f#2]"

# High doubling (sparse)
d5 $ note "d4 ~ d4 ~ [a4 g#4 g4 f#4] ~"

# Organ pad drone (pair 1)
d6 $ voices "1 2" # hold "0.6"

# Bass drone (pair 2)
d7 $ slow 2 $ voices "3 ~ 4 ~"

# FM texture (pair 3, euclidean)
d8 $ voices "5(3,8) 6(5,8)"
            """.trimIndent(),

            "euclidean" to """
# Euclidean polyrhythms
d1 $ note "c3(3,8) e3(5,8) g3(7,8)"
d2 $ s "bd(3,8) sn(5,16) hh(7,12)"
d3 $ slow 2 $ note "<c2 g2>(3,8)"
drive:0.3
feedback:0.4
delaymix:0.2
            """.trimIndent(),

            "ambient" to """
bpm 72
drive:0.2
feedback:0.6
delaymix:0.4

# Slow evolving pads
d1 $ slow 4 $ note "<c3 e3 g3> <b2 d3 f#3>"
d2 $ slow 8 $ note "<c2 g2>"
d3 $ voices "1 ~ 2 ~" # hold "0.8"
            """.trimIndent(),

            "drums" to """
bpm 125
drive:0.3
distmix:0.35
feedback:0.2
delaymix:0.15

# Kick - steady pulse, extra hit every 4th bar
d1 $ s "<[bd ~ ~ ~ bd ~ ~ ~] [bd ~ ~ ~ bd ~ ~ ~] [bd ~ ~ ~ bd ~ bd ~] [bd ~ bd ~ bd ~ ~ bd]>"

# Snare backbeat + rimshot ghost notes
d2 $ s "<[~ ~ sn ~ ~ ~ sn ~] [~ ~ sn ~ ~ rim sn ~] [~ ~ sn ~ ~ ~ sn ~] [~ ~ sn ~ rim sn [sn sn] ~]>"

# Hi-hats - 8ths with open hat accents
d3 $ s "<[hh hh hh hh hh hh hh hh] [hh hh hh hh hh hh oh hh] [hh hh oh hh hh hh hh hh] [hh hh hh hh oh hh oh oh]>"

# Toms fill every 4th cycle
d4 $ slow 4 $ s "~ ~ ~ [ht ht mt mt lt lt lt bd]"

# Cowbell + clap accents
d5 $ s "<[~ ~ ~ cb ~ ~ ~ ~] [~ cp ~ ~ ~ ~ cb ~] [~ ~ ~ cb ~ cp ~ ~] [~ cp ~ cb ~ cp cb ~]>"

# String bass follows the groove
engine:1 string
tune:1 0.562
envspeed:1 0.5
d6 $ voices "1 ~ 1 ~"
            """.trimIndent(),

            "engines" to """
bpm 100

# String bass (pair 1, voices 1-2)
engine:1 string
tune:1 0.562
tune:2 0.562
envspeed:1 0.7
envspeed:2 0.7

# FM lead (pair 2, voices 3-4)
engine:2 fm
tune:3 0.646
tune:4 0.708
envspeed:3 0.3
envspeed:4 0.3

# Modal bells (pair 3, voices 5-6)
engine:3 modal
tune:5 0.750
tune:6 0.562
envspeed:5 0.2
envspeed:6 0.2

drive:0.2
feedback:0.4
delaymix:0.3

d1 $ slow 2 $ voices "1 ~ 2 ~"
d2 $ voices "3 ~ 4 3"
d3 $ voices "5(3,8) 6(5,8)"
d4 $ s "bd ~ sn ~"
d5 $ s "~ hh ~ hh"
            """.trimIndent(),

            "polyrhythm" to """
bpm 140
drive:0.3
feedback:0.35
delaymix:0.25

# Layered polyrhythmic patterns
d1 $ note "[c3 e3 g3]*2"
d2 $ note "c4(3,8) e4(5,8)"
d3 $ s "bd(3,8) sn(5,16)"
d4 $ slow 2 $ note "<c2 g2 e2 b2>"
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
