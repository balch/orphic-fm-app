package org.balch.orpheus.features.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.core.di.FeatureScope

/** A live agent activity event for the VIBE panel feed. Extensible: add Reasoning(text) when token streaming lands. */
sealed interface AgentActivityEvent {
    data class ToolStarted(val tool: String) : AgentActivityEvent
    data class ToolCompleted(val tool: String) : AgentActivityEvent
    data class Assistant(val text: String) : AgentActivityEvent
    data class Reasoning(val text: String) : AgentActivityEvent
}

@SingleIn(FeatureScope::class)
@Inject
class AgentActivityEventBus {

    private val log = logging("AgentActivityEventBus")

    // replay = 0: a fresh generation run never replays stale activity.
    private val _events = MutableSharedFlow<AgentActivityEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<AgentActivityEvent> = _events.asSharedFlow()

    fun emitToolStarted(tool: String) { _events.tryEmit(AgentActivityEvent.ToolStarted(tool)) }
    fun emitToolCompleted(tool: String) { _events.tryEmit(AgentActivityEvent.ToolCompleted(tool)) }
    fun emitAssistant(text: String) { _events.tryEmit(AgentActivityEvent.Assistant(text)) }
    fun emitReasoning(text: String) { _events.tryEmit(AgentActivityEvent.Reasoning(text)) }
}
