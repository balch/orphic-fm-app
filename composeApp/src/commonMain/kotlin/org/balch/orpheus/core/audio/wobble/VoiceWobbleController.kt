package org.balch.orpheus.core.audio.wobble

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sign

/**
 * Controller for managing Voice Wobble behavior across voices.
 * 
 * Wobble allows finger movement while holding a PulseButton to modulate a sound parameter.
 * The calculation: baseValue * (1 + wobbleOffset * range)
 * 
 * When wobble is applied on top of envelope speed:
 * 1. Envelope speed determines attack/decay characteristics
 * 2. Wobble applies real-time modulation on top of the current envelope level
 */
@SingleIn(AppScope::class)
@Inject
class VoiceWobbleController {
    
    private val _config = MutableStateFlow(VoiceWobbleConfig())
    val config: StateFlow<VoiceWobbleConfig> = _config.asStateFlow()
    
    private val _voiceWobbleStates = MutableStateFlow(List(8) { VoiceWobbleState() })
    val voiceWobbleStates: StateFlow<List<VoiceWobbleState>> = _voiceWobbleStates.asStateFlow()
    
    /**
     * Update the global wobble configuration.
     */
    fun updateConfig(newConfig: VoiceWobbleConfig) {
        _config.value = newConfig
    }
    
    /**
     * Called when a pulse starts on a voice.
     * Initializes wobble tracking for the voice.
     */
    fun onPulseStart(voiceIndex: Int, initialX: Float, initialY: Float) {
        if (voiceIndex !in 0..7) return
        
        val states = _voiceWobbleStates.value.toMutableList()
        states[voiceIndex] = VoiceWobbleState(
            isActive = true,
            wobbleOffset = 0f,
            smoothedWobble = 0f,
            lastPointerX = initialX,
            lastPointerY = initialY,
            accumulatedVelocity = 0f
        )
        _voiceWobbleStates.value = states
    }
    
    /**
     * Called continuously as the pointer moves while pulse is active.
     * Returns the current wobble modulation value.
     * 
     * Uses direction-agnostic oscillation: movement magnitude drives the wobble,
     * and direction changes (back and forth motion) cause the wobble to alternate
     * between positive and negative.
     * 
     * @return Wobble offset in range [-1, 1]
     */
    fun onPointerMove(voiceIndex: Int, x: Float, y: Float): Float {
        if (voiceIndex !in 0..7) return 0f
        
        val config = _config.value
        if (!config.enabled) return 0f
        
        val states = _voiceWobbleStates.value.toMutableList()
        val currentState = states[voiceIndex]
        
        if (!currentState.isActive) return 0f
        
        // Calculate movement delta (both X and Y contribute)
        val deltaX = x - currentState.lastPointerX
        val deltaY = y - currentState.lastPointerY
        val movementMagnitude = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
        
        // Direction-agnostic wobble: use movement to push toward extremes,
        // then decay back toward center. Each direction change flips the target.
        // This creates an oscillating effect when wiggling.
        
        // Determine if direction changed (using dominant axis)
        val currentDirection = if (kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)) {
            if (deltaY > 0) 1 else -1
        } else {
            if (deltaX > 0) 1 else -1
        }
        
        // Calculate target wobble: alternate based on direction, scaled by movement
        val wobbleImpulse = movementMagnitude * config.sensitivity * 0.05f * currentDirection
        
        // Add impulse to accumulated velocity
        val rawVelocity = currentState.accumulatedVelocity + wobbleImpulse
        
        // Apply strong decay to create oscillation back toward center
        val decayedVelocity = rawVelocity * 0.7f
        
        // Calculate wobble offset clamped to range
        val wobbleOffset = decayedVelocity.coerceIn(-1f, 1f)
        
        // Apply smoothing for audio-rate transitions
        val smoothedWobble = currentState.smoothedWobble + 
            (wobbleOffset - currentState.smoothedWobble) * (1f - config.smoothing)
        
        states[voiceIndex] = currentState.copy(
            lastPointerX = x,
            lastPointerY = y,
            accumulatedVelocity = decayedVelocity,
            wobbleOffset = wobbleOffset,
            smoothedWobble = smoothedWobble
        )
        _voiceWobbleStates.value = states
        
        return smoothedWobble
    }
    
    /**
     * Called when a pulse ends on a voice.
     * Captures the final wobble state and resets tracking.
     * 
     * @return The final wobble modulation value at release
     */
    fun onPulseEnd(voiceIndex: Int): Float {
        if (voiceIndex !in 0..7) return 0f
        
        val states = _voiceWobbleStates.value.toMutableList()
        val currentState = states[voiceIndex]
        val finalWobble = currentState.smoothedWobble
        
        states[voiceIndex] = VoiceWobbleState(isActive = false)
        _voiceWobbleStates.value = states
        
        return finalWobble
    }
    
    /**
     * Apply wobble modulation to a base value.
     * 
     * @param baseValue The value from the envelope or current parameter level
     * @param wobbleOffset The wobble offset from [-1, 1]
     * @return The modulated value
     */
    fun applyWobble(baseValue: Float, wobbleOffset: Float): Float {
        val config = _config.value
        if (!config.enabled) return baseValue
        
        // Apply wobble as a multiplicative modulation
        // wobbleOffset of 1 at range 0.3 = 30% increase
        // wobbleOffset of -1 at range 0.3 = 30% decrease
        val modulation = 1f + (wobbleOffset * config.range)
        
        return (baseValue * modulation).coerceIn(0f, 1f)
    }
    
    /**
     * Get the current wobble offset for a voice.
     */
    fun getWobbleOffset(voiceIndex: Int): Float {
        if (voiceIndex !in 0..7) return 0f
        return _voiceWobbleStates.value[voiceIndex].smoothedWobble
    }
    
    /**
     * Check if wobble is currently active for a voice.
     */
    fun isWobbleActive(voiceIndex: Int): Boolean {
        if (voiceIndex !in 0..7) return false
        return _voiceWobbleStates.value[voiceIndex].isActive
    }
}
