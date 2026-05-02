package org.balch.orpheus.core.audio

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.PlaybackController
import kotlin.concurrent.Volatile

/**
 * Engine lifecycle wrapper. Owns:
 * - Engine start/stop (process boot, shutdown).
 * - Restoring master volume after engine restart.
 * - Routing PlaybackLifecycleEvent.RequestResume to PlaybackController.
 * - Clearing activity sources on StopAll.
 *
 * Does NOT own play/pause state, MediaSession activation, mute mechanism,
 * or playback mode display — those are PlaybackController's responsibility
 * (see core/playback/PlaybackController.kt).
 */
@SingleIn(AppScope::class)
@Inject
class SynthOrchestrator(
    private val engine: SynthEngine,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val playbackController: PlaybackController,
    private val scope: AppCoroutineScope,
) {
    private val log = logging("SynthOrchestrator")
    @Volatile private var isStarted = false
    @Volatile private var savedMasterVolume = 0.7f

    init {
        // Route lifecycle events through PlaybackController so all state
        // transitions go through the single source of truth.
        scope.launch {
            playbackLifecycleManager.events.collect { event ->
                when (event) {
                    is PlaybackLifecycleEvent.RequestResume -> {
                        log.debug { "Received RequestResume event" }
                        playbackController.play()
                    }
                    is PlaybackLifecycleEvent.StopAll -> {
                        // Clear activity sources so the MediaSession can fully
                        // deactivate (PlaybackController observes this).
                        mediaSessionStateManager.clearAll()
                    }
                }
            }
        }
    }

    /**
     * Start the synth engine. Called once at app boot.
     */
    fun start() {
        if (!isStarted) {
            engine.start()
            // Restore master volume after starting (engine may reset to 0 on restart).
            engine.setMasterVolume(savedMasterVolume)
            log.debug { "Restored master volume to $savedMasterVolume after start" }
            isStarted = true
            log.debug { "SynthOrchestrator: Engine started" }
        }
    }

    /**
     * Stop the engine. Called on app shutdown (JVM main).
     */
    fun stop() {
        if (isStarted) {
            log.debug { "SynthOrchestrator: Stopping - broadcasting stop event" }
            // Broadcast stop event to all listeners (agents, schedulers, etc.).
            playbackLifecycleManager.tryRequestStopAll()
            // Save current master volume for next restart.
            savedMasterVolume = engine.getMasterVolume()
            log.debug { "Saved master volume: $savedMasterVolume for next start" }
            engine.stop()
            isStarted = false
            log.debug { "SynthOrchestrator: Engine stopped" }
        }
    }

    val peakFlow get() = engine.peakFlow
}
