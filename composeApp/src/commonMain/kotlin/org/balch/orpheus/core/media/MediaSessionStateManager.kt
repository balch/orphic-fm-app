package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Source of audio activity that can require MediaSession to be active.
 */
enum class AudioActivitySource {
    EVO,      // Audio Evolution is enabled
    REPL,     // REPL is playing patterns
    DRONE,    // AI Drone mode is active
    SOLO      // AI Solo mode is active
}

/**
 * Centralized manager for MediaSession state.
 * 
 * Tracks whether MediaSession should be active based on multiple audio activity sources:
 * - Audio Evo is Enabled
 * - REPL is playing
 * - AI DroneMode is enabled
 * - AI SoloMode is enabled
 * 
 * The MediaSession should be:
 * - ACTIVE if any of the above sources are active
 * - DELETED/DEACTIVATED if none are active
 */
@SingleIn(AppScope::class)
@Inject
class MediaSessionStateManager {
    private val log = logging("MediaSessionStateManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Individual activity states
    private val _isEvoActive = MutableStateFlow(false)
    private val _isReplPlaying = MutableStateFlow(false)
    private val _isDroneActive = MutableStateFlow(false)
    private val _isSoloActive = MutableStateFlow(false)
    
    /**
     * Combined flow indicating whether MediaSession should be active.
     * True if ANY source is active, false if ALL are inactive.
     */
    private val _isMediaSessionNeeded = MutableStateFlow(false)
    val isMediaSessionNeeded: StateFlow<Boolean> = _isMediaSessionNeeded.asStateFlow()
    
    /**
     * Current reason for MediaSession being active (for logging/debugging).
     * Returns the first active source, or null if none.
     */
    private val _activeSource = MutableStateFlow<AudioActivitySource?>(null)
    val activeSource: StateFlow<AudioActivitySource?> = _activeSource.asStateFlow()
    
    init {
        // Combine all sources and update the isMediaSessionNeeded state
        scope.launch {
            combine(
                _isEvoActive,
                _isReplPlaying,
                _isDroneActive,
                _isSoloActive
            ) { evo, repl, drone, solo ->
                val needed = evo || repl || drone || solo
                val source = when {
                    evo -> AudioActivitySource.EVO
                    repl -> AudioActivitySource.REPL
                    drone -> AudioActivitySource.DRONE
                    solo -> AudioActivitySource.SOLO
                    else -> null
                }
                needed to source
            }.collect { (needed, source) ->
                if (_isMediaSessionNeeded.value != needed) {
                    log.info { "MediaSession needed: $needed (source: $source)" }
                    _isMediaSessionNeeded.value = needed
                    _activeSource.value = source
                }
            }
        }
    }
    
    /**
     * Update the Evo activity state.
     */
    fun setEvoActive(active: Boolean) {
        if (_isEvoActive.value != active) {
            log.debug { "Evo active: $active" }
            _isEvoActive.value = active
        }
    }
    
    /**
     * Update the REPL playing state.
     */
    fun setReplPlaying(playing: Boolean) {
        if (_isReplPlaying.value != playing) {
            log.debug { "REPL playing: $playing" }
            _isReplPlaying.value = playing
        }
    }
    
    /**
     * Update the Drone mode active state.
     */
    fun setDroneActive(active: Boolean) {
        if (_isDroneActive.value != active) {
            log.debug { "Drone active: $active" }
            _isDroneActive.value = active
        }
    }
    
    /**
     * Update the Solo mode active state.
     */
    fun setSoloActive(active: Boolean) {
        if (_isSoloActive.value != active) {
            log.debug { "Solo active: $active" }
            _isSoloActive.value = active
        }
    }
    
    /**
     * Clear all activity states (e.g., when stopping everything).
     */
    fun clearAll() {
        log.info { "Clearing all activity states" }
        _isEvoActive.value = false
        _isReplPlaying.value = false
        _isDroneActive.value = false
        _isSoloActive.value = false
    }
}
