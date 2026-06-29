package org.balch.orpheus.features.pulsar

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.features.pulsar.models.Vibe

/** An agent-driven vibe-creation event, mirrored on the REPL's ReplCodeEvent. */
sealed interface VibeCreateEvent {
    data object Generating : VibeCreateEvent
    data class Generated(val vibe: Vibe) : VibeCreateEvent
    data class Failed(val error: String) : VibeCreateEvent
}

/** Bridges the AI vibe tools (producer, in :features:ai) to the VIBE panel VM (consumer, here). */
@SingleIn(AppScope::class)
@Inject
class VibeCreateEventBus() {

    private val log = logging("VibeCreateEventBus")

    private val _events = MutableSharedFlow<VibeCreateEvent>(replay = 1, extraBufferCapacity = 5)
    val events: SharedFlow<VibeCreateEvent> = _events.asSharedFlow()

    suspend fun emitGenerating() {
        log.debug { "Generating" }
        _events.emit(VibeCreateEvent.Generating)
    }

    suspend fun emitGenerated(vibe: Vibe) {
        log.debug { "Generated ${vibe.name}" }
        _events.emit(VibeCreateEvent.Generated(vibe))
    }

    suspend fun emitFailed(error: String) {
        log.warn { "Failed - $error" }
        _events.emit(VibeCreateEvent.Failed(error))
    }
}
