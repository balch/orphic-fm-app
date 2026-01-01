package org.balch.orpheus.core.lifecycle

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lifecycle events for the playback system.
 */
sealed interface PlaybackLifecycleEvent {
    /**
     * Request to stop all audio playback, agents, and schedulers.
     * Emitted when the foreground service is stopped or the app/synth is shutting down.
     */
    data object StopAll : PlaybackLifecycleEvent
    
    /**
     * Request to resume/unmute playback.
     * Emitted when any component starts playing (e.g., TidalScheduler).
     */
    data object RequestResume : PlaybackLifecycleEvent
}

/**
 * Manages playback lifecycle events across the app.
 * 
 * This allows components like the foreground service to signal a "stop everything"
 * event that agents, schedulers, and other audio producers can respond to.
 * 
 * Components should subscribe to [events] and stop their audio-producing operations
 * when they receive [PlaybackLifecycleEvent.StopAll].
 */
@SingleIn(AppScope::class)
@Inject
class PlaybackLifecycleManager {
    private val log = logging("PlaybackLifecycleManager")
    
    private val _events = MutableSharedFlow<PlaybackLifecycleEvent>(extraBufferCapacity = 1)
    
    /**
     * Event stream for lifecycle events.
     * Subscribe to this to be notified of stop requests.
     */
    val events: SharedFlow<PlaybackLifecycleEvent> = _events.asSharedFlow()
    
    /**
     * Emit a request to stop all playback.
     * This should be called when the foreground service stops or the synth shuts down.
     */
    suspend fun requestStopAll() {
        log.info { "Requesting stop all playback" }
        _events.emit(PlaybackLifecycleEvent.StopAll)
    }
    
    /**
     * Non-suspending version for use from callbacks.
     */
    fun tryRequestStopAll() {
        log.info { "Requesting stop all playback (try)" }
        _events.tryEmit(PlaybackLifecycleEvent.StopAll)
    }
    
    /**
     * Request to resume playback (unmute audio).
     * Called when a component starts playing.
     */
    fun tryRequestResume() {
        log.debug { "Requesting resume playback" }
        _events.tryEmit(PlaybackLifecycleEvent.RequestResume)
    }
}
