package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope

/**
 * Source of audio activity that can require MediaSession to be active.
 */
enum class AudioActivitySource {
    EVO,           // Audio Evolution is enabled
    REPL,          // REPL is playing patterns
    DRONE,         // AI Drone mode is active
    SOLO,          // AI Solo mode is active
    TIMER,         // Sleep timer countdown is active
    PULSAR,        // Pulsar beat machine is active
    AUTO_BROWSER   // Android Auto (or other MediaBrowser client) is bound
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
class MediaSessionStateManager(private val scope: AppCoroutineScope) {
    private val log = logging("MediaSessionStateManager")
    
    // Individual activity states
    private val _isEvoActive = MutableStateFlow(false)
    /** Evo activity, exposed for metadata producers that want a display chip. */
    val isEvoActive: StateFlow<Boolean> = _isEvoActive.asStateFlow()
    private val _isReplPlaying = MutableStateFlow(false)
    private val _isDroneActive = MutableStateFlow(false)
    private val _isSoloActive = MutableStateFlow(false)
    private val _isTimerActive = MutableStateFlow(false)
    private val _isPulsarActive = MutableStateFlow(false)
    private val _isAutoBrowserActive = MutableStateFlow(false)

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
                _isSoloActive,
                _isTimerActive,
                _isPulsarActive,
                _isAutoBrowserActive,
            ) { states ->
                val evo = states[0]
                val repl = states[1]
                val drone = states[2]
                val solo = states[3]
                val timer = states[4]
                val pulsar = states[5]
                val autoBrowser = states[6]
                val needed = evo || repl || drone || solo || timer || pulsar || autoBrowser
                val source = when {
                    timer -> AudioActivitySource.TIMER
                    pulsar -> AudioActivitySource.PULSAR
                    evo -> AudioActivitySource.EVO
                    repl -> AudioActivitySource.REPL
                    drone -> AudioActivitySource.DRONE
                    solo -> AudioActivitySource.SOLO
                    autoBrowser -> AudioActivitySource.AUTO_BROWSER
                    else -> null
                }
                needed to source
            }.collect { (needed, source) ->
                if (_isMediaSessionNeeded.value != needed) {
                    log.debug { "MediaSession needed: $needed (source: $source)" }
                    _isMediaSessionNeeded.value = needed
                }
                if (_activeSource.value != source) {
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
     * Update the timer active state.
     */
    fun setTimerActive(active: Boolean) {
        if (_isTimerActive.value != active) {
            log.debug { "Timer active: $active" }
            _isTimerActive.value = active
        }
    }

    /**
     * Update the Pulsar active state.
     */
    fun setPulsarActive(active: Boolean) {
        if (_isPulsarActive.value != active) {
            log.debug { "Pulsar active: $active" }
            _isPulsarActive.value = active
        }
    }

    /**
     * Update the Auto-browser bound state.
     *
     * Set to true while a MediaBrowser client (Android Auto, Google Assistant)
     * has the browser service bound. Keeps the MediaSession active so that
     * action handlers are wired up before the first playback command arrives.
     */
    fun setAutoBrowserActive(active: Boolean) {
        if (_isAutoBrowserActive.value != active) {
            log.debug { "Auto-browser active: $active" }
            _isAutoBrowserActive.value = active
        }
    }

    /**
     * Clear all activity states (e.g., when stopping everything).
     */
    fun clearAll() {
        log.debug { "Clearing all activity states" }
        _isEvoActive.value = false
        _isReplPlaying.value = false
        _isDroneActive.value = false
        _isSoloActive.value = false
        _isTimerActive.value = false
        _isPulsarActive.value = false
        _isAutoBrowserActive.value = false
    }
}
