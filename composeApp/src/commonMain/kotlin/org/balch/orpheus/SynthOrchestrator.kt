package org.balch.orpheus

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionActionHandler
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.PlaybackMetadata
import org.balch.orpheus.core.media.PlaybackMode

/**
 * Orchestrates the lifecycle of the synthesizer engine.
 * 
 * Manages engine start/stop to ensure proper audio lifecycle management
 * independent of UI composition. Also manages the MediaSession for
 * background audio and system media controls.
 * 
 * Supports three states:
 * - Playing: Audio is audible, engine is running
 * - Paused: Audio is muted, but engine and agents keep running
 * - Stopped: Engine stopped, agents stopped, service deactivated
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isStarted = false
    private var isPaused = false
    private var isMediaSessionActive = false
    private var savedMasterVolume = 1f
    private var currentPlaybackMode = PlaybackMode.USER

    init {
        mediaSessionManager.setActionHandler(this)
        
        // Subscribe to lifecycle events (e.g., resume requests from scheduler)
        scope.launch {
            playbackLifecycleManager.events.collect { event ->
                when (event) {
                    is PlaybackLifecycleEvent.RequestResume -> {
                        log.debug { "Received RequestResume event" }
                        // Activate media session when actual playback starts (e.g., REPL)
                        ensureMediaSessionActive()
                        updateMediaSessionMetadata()
                        if (isPaused) {
                            resume()
                        }
                    }
                    is PlaybackLifecycleEvent.StopAll -> {
                        // Handled by the emitting stop(), no action needed here
                    }
                }
            }
        }
    }

    /**
     * Start the synth engine. This does NOT activate the media session.
     * The media session is only activated when actual playback begins
     * (REPL, Drone, Solo mode) to avoid showing a "Playing" notification
     * when nothing is actually playing.
     */
    fun start() {
        if (!isStarted) {
            engine.start()
            // Restore master volume after starting (engine may reset to 0 on restart)
            engine.setMasterVolume(savedMasterVolume)
            log.debug { "Restored master volume to $savedMasterVolume after start" }
            
            // Don't activate media session here - wait for actual playback
            isStarted = true
            isPaused = false
            log.info { "SynthOrchestrator: Engine started (media session not yet active)" }
        }
    }
    
    /**
     * Activate the media session if not already active.
     * Called when actual playback begins (REPL, Drone, Solo).
     */
    private fun ensureMediaSessionActive() {
        if (!isMediaSessionActive && isStarted) {
            mediaSessionManager.activate()
            mediaSessionManager.updatePlaybackState(true)
            isMediaSessionActive = true
            log.info { "SynthOrchestrator: Media session activated" }
        }
    }
    
    /**
     * Pause audio playback by muting output.
     * The engine and all agents continue running, just silenced.
     */
    fun pause() {
        if (isStarted && !isPaused) {
            // Save current master volume and mute
            savedMasterVolume = engine.getMasterVolume()
            engine.setMasterVolume(0f)
            mediaSessionManager.updatePlaybackState(false)
            isPaused = true
            updateMediaSessionMetadata()
            log.info { "SynthOrchestrator: Paused (muted, savedVolume=$savedMasterVolume)" }
        }
    }
    
    /**
     * Resume audio playback by restoring volume.
     */
    fun resume() {
        if (isStarted && isPaused) {
            // Restore master volume
            engine.setMasterVolume(savedMasterVolume)
            mediaSessionManager.updatePlaybackState(true)
            isPaused = false
            updateMediaSessionMetadata()
            log.info { "SynthOrchestrator: Resumed (restored volume=$savedMasterVolume)" }
        }
    }

    fun stop() {
        if (isStarted) {
            log.info { "SynthOrchestrator: Stopping - broadcasting stop event" }
            // Broadcast stop event to all listeners (agents, schedulers, etc.)
            playbackLifecycleManager.tryRequestStopAll()
            
            // Save the current master volume before stopping
            // If paused, we already have the pre-pause volume saved
            // If not paused, save the current volume for next restart
            if (!isPaused) {
                savedMasterVolume = engine.getMasterVolume()
            }
            log.debug { "Saved master volume: $savedMasterVolume for next start" }
            
            // Only deactivate media session if it was active
            if (isMediaSessionActive) {
                mediaSessionManager.updatePlaybackState(false)
                mediaSessionManager.deactivate()
                isMediaSessionActive = false
            }
            engine.stop()
            isStarted = false
            isPaused = false
            log.info { "SynthOrchestrator: Engine stopped" }
        }
    }

    val peakFlow get() = engine.peakFlow
    
    // MediaSessionActionHandler implementation
    override fun onPlay() {
        log.info { "MediaSession: Play requested" }
        if (!isStarted) {
            start()
        } else if (isPaused) {
            resume()
        }
    }
    
    override fun onPause() {
        log.info { "MediaSession: Pause requested" }
        pause()
    }
    
    override fun onStop() {
        log.info { "MediaSession: Stop requested" }
        stop()
    }
    
    /**
     * Set the current playback mode for display in notifications and lock screen.
     * Call this when AI modes are activated/deactivated.
     * 
     * Also activates the media session if entering a playing mode (DRONE, SOLO, REPL).
     */
    fun setPlaybackMode(mode: PlaybackMode) {
        if (currentPlaybackMode != mode) {
            currentPlaybackMode = mode
            log.info { "Playback mode changed to: ${mode.displayName}" }
            
            // Activate media session when entering a playing mode
            if (mode != PlaybackMode.USER) {
                ensureMediaSessionActive()
            }
            updateMediaSessionMetadata()
        }
    }
    
    /**
     * Update MediaSession metadata with current mode and state.
     */
    private fun updateMediaSessionMetadata() {
        if (!isMediaSessionActive) return
        
        val metadata = PlaybackMetadata(
            title = "Orpheus Synthesizer",
            mode = currentPlaybackMode,
            isPlaying = !isPaused
        )
        mediaSessionManager.updateMetadata(metadata)
    }
}

