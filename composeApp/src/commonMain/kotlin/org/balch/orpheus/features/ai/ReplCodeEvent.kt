package org.balch.orpheus.features.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Represents an AI-generated REPL code event.
 */
sealed interface ReplCodeEvent {
    /**
     * AI is starting to generate REPL code.
     */
    data object Generating : ReplCodeEvent
    
    /**
     * AI successfully generated and executed REPL code.
     */
    data class Generated(
        val code: String,
        val slots: List<String>
    ) : ReplCodeEvent
    
    /**
     * AI failed to generate REPL code.
     */
    data class Failed(val error: String) : ReplCodeEvent
    
    /**
     * User took manual control of the REPL (stop, execute, etc.)
     * This signals that REPL mode should be deactivated.
     */
    data object UserInteraction : ReplCodeEvent
}

/**
 * Event bus for AI-generated REPL code.
 * 
 * This allows the ReplExecuteTool to emit events that can be observed
 * by the LiveCodeViewModel to update the CODE panel, and by the
 * AiOptionsViewModel to track generation state.
 */
@SingleIn(AppScope::class)
class ReplCodeEventBus @Inject constructor() {
    
    private val log = logging("ReplCodeEventBus")
    
    private val _events = MutableSharedFlow<ReplCodeEvent>(
        replay = 1,  // Replay for late subscribers
        extraBufferCapacity = 5
    )
    
    /**
     * Flow of REPL code events.
     */
    val events: SharedFlow<ReplCodeEvent> = _events.asSharedFlow()
    
    /**
     * Emit that REPL generation is starting.
     */
    suspend fun emitGenerating() {
        log.debug { "ReplCodeEventBus: Generating" }
        _events.emit(ReplCodeEvent.Generating)
    }
    
    /**
     * Emit that REPL code was generated successfully.
     */
    suspend fun emitGenerated(code: String, slots: List<String>) {
        log.info { "ReplCodeEventBus: Generated code (${code.length} chars)" }
        _events.emit(ReplCodeEvent.Generated(code, slots))
    }
    
    /**
     * Emit that REPL generation failed.
     */
    suspend fun emitFailed(error: String) {
        log.warn { "ReplCodeEventBus: Failed - $error" }
        _events.emit(ReplCodeEvent.Failed(error))
    }
    
    /**
     * Emit that user took manual control of the REPL.
     */
    suspend fun emitUserInteraction() {
        log.debug { "ReplCodeEventBus: UserInteraction" }
        _events.emit(ReplCodeEvent.UserInteraction)
    }
}
