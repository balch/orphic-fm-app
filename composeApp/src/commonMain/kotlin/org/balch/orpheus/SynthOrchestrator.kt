package org.balch.orpheus

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionActionHandler
import org.balch.orpheus.core.media.MediaSessionManager

/**
 * Orchestrates the lifecycle of the synthesizer engine.
 * 
 * Manages engine start/stop to ensure proper audio lifecycle management
 * independent of UI composition. Also manages the MediaSession for
 * background audio and system media controls.
 * 
 * When stopped (including from the foreground service notification),
 * broadcasts a stop event via [PlaybackLifecycleManager] so all 
 * audio-producing components (AI agents, schedulers) can shut down.
 */
@SingleIn(AppScope::class)
@Inject
class SynthOrchestrator(
    private val engine: SynthEngine,
    private val mediaSessionManager: MediaSessionManager,
    private val playbackLifecycleManager: PlaybackLifecycleManager
) : MediaSessionActionHandler {
    private val log = logging("SynthOrchestrator")
    private var isStarted = false

    init {
        mediaSessionManager.setActionHandler(this)
    }

    fun start() {
        if (!isStarted) {
            engine.start()
            mediaSessionManager.activate()
            mediaSessionManager.updatePlaybackState(true)
            isStarted = true
            log.info { "SynthOrchestrator: Engine started" }
        }
    }

    fun stop() {
        if (isStarted) {
            log.info { "SynthOrchestrator: Stopping - broadcasting stop event" }
            // Broadcast stop event to all listeners (agents, schedulers, etc.)
            playbackLifecycleManager.tryRequestStopAll()
            
            mediaSessionManager.updatePlaybackState(false)
            mediaSessionManager.deactivate()
            engine.stop()
            isStarted = false
            log.info { "SynthOrchestrator: Engine stopped" }
        }
    }

    val peakFlow get() = engine.peakFlow
    
    // MediaSessionActionHandler implementation
    override fun onPlay() {
        log.info { "MediaSession: Play requested" }
        start()
    }
    
    override fun onPause() {
        log.info { "MediaSession: Pause requested" }
        stop()
    }
    
    override fun onStop() {
        log.info { "MediaSession: Stop requested" }
        stop()
    }
}
