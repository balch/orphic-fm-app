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
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMetadata
import org.balch.orpheus.core.media.PlaybackMode

/**
 * Orchestrates the lifecycle of the synthesizer engine.
 * 
 * Manages engine start/stop to ensure proper audio lifecycle management
 * independent of UI composition. Also manages the MediaSession for
 * background audio and system media controls.
 * 
 * MediaSession is automatically managed based on [MediaSessionStateManager]:
 * - ACTIVATED when any audio source is active (Evo, REPL, Drone, Solo)
 * - DEACTIVATED when all audio sources become inactive
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
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val mediaSessionStateManager: MediaSessionStateManager
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
        
        // Subscribe to MediaSessionStateManager to auto-activate/deactivate MediaSession
        scope.launch {
            mediaSessionStateManager.isMediaSessionNeeded.collect { needed ->
                if (needed && !isMediaSessionActive && isStarted) {
                    log.debug { "MediaSession needed - activating (source: ${mediaSessionStateManager.activeSource.value})" }
                    activateMediaSession()
                } else if (!needed && isMediaSessionActive) {
                    log.debug { "MediaSession no longer needed - deactivating" }
                    deactivateMediaSession()
                }
            }
        }
        
        // Subscribe to lifecycle events (e.g., resume requests from scheduler)
        scope.launch {
            playbackLifecycleManager.events.collect { event ->
                when (event) {
                    is PlaybackLifecycleEvent.RequestResume -> {
                        log.debug { "Received RequestResume event" }
                        // Update metadata when playback resumes
                        updateMediaSessionMetadata()
                        if (isPaused) {
                            resume()
                        }
                    }
                    is PlaybackLifecycleEvent.StopAll -> {
                        // Clear all activity states when stopping
                        mediaSessionStateManager.clearAll()
                    }
                }
            }
        }
    }
    
    /**
     * Activate the MediaSession (internal).
     */
    private fun activateMediaSession() {
        if (!isMediaSessionActive && isStarted) {
            mediaSessionManager.activate()
            mediaSessionManager.updatePlaybackState(!isPaused)
            isMediaSessionActive = true
            updateMediaSessionMetadata()
            log.debug { "SynthOrchestrator: Media session activated" }
        }
    }
    
    /**
     * Deactivate the MediaSession (internal).
     */
    private fun deactivateMediaSession() {
        if (isMediaSessionActive) {
            mediaSessionManager.updatePlaybackState(false)
            mediaSessionManager.deactivate()
            isMediaSessionActive = false
            // Reset playback mode to USER when session deactivates
            currentPlaybackMode = PlaybackMode.USER
            log.debug { "SynthOrchestrator: Media session deactivated" }
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
            log.debug { "SynthOrchestrator: Engine started (media session not yet active)" }
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
            log.debug { "SynthOrchestrator: Paused (muted, savedVolume=$savedMasterVolume)" }
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
            log.debug { "SynthOrchestrator: Resumed (restored volume=$savedMasterVolume)" }
        }
    }

    fun stop() {
        if (isStarted) {
            log.debug { "SynthOrchestrator: Stopping - broadcasting stop event" }
            // Broadcast stop event to all listeners (agents, schedulers, etc.)
            playbackLifecycleManager.tryRequestStopAll()
            
            // Save the current master volume before stopping
            // If paused, we already have the pre-pause volume saved
            // If not paused, save the current volume for next restart
            if (!isPaused) {
                savedMasterVolume = engine.getMasterVolume()
            }
            log.debug { "Saved master volume: $savedMasterVolume for next start" }
            
            // Deactivate media session using the centralized method
            deactivateMediaSession()
            engine.stop()
            isStarted = false
            isPaused = false
            log.debug { "SynthOrchestrator: Engine stopped" }
        }
    }

    val peakFlow get() = engine.peakFlow
    
    // MediaSessionActionHandler implementation
    override fun onPlay() {
        log.debug { "MediaSession: Play requested" }
        if (!isStarted) {
            start()
        } else if (isPaused) {
            resume()
        }
    }
    
    override fun onPause() {
        log.debug { "MediaSession: Pause requested" }
        pause()
    }
    
    override fun onStop() {
        log.debug { "MediaSession: Stop requested" }
        stop()
    }
    
    /**
     * Set the current playback mode for display in notifications and lock screen.
     * Call this when AI modes are activated/deactivated.
     * 
     * Note: MediaSession activation is now handled by MediaSessionStateManager.
     * This method only updates the mode for metadata display.
     */
    fun setPlaybackMode(mode: PlaybackMode) {
        if (currentPlaybackMode != mode) {
            currentPlaybackMode = mode
            log.debug { "Playback mode changed to: ${mode.displayName}" }
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

