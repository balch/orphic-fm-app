package org.balch.orpheus.core.audio.wobble

/**
 * Configuration for the Voice Wobble effect.
 * Wobble allows finger movement while holding a PulseButton to modulate a parameter.
 */
data class VoiceWobbleConfig(
    /**
     * Target parameter to modulate.
     * Maps to ControlId strings from MidiMappingState.
     */
    val targetParameter: VoiceWobbleTarget = VoiceWobbleTarget.VOICE_VOLUME,
    
    /**
     * Sensitivity of wobble detection.
     * Higher values = more responsive to small movements.
     * Range: 0.1 to 5.0, default: 1.0
     */
    val sensitivity: Float = 1.0f,
    
    /**
     * Maximum modulation range.
     * 0.5 means the parameter can wobble ±50% of its current value.
     * Range: 0.0 to 1.0, default: 0.3
     */
    val range: Float = 0.3f,
    
    /**
     * Smoothing factor for wobble transitions.
     * Higher values = smoother but slower response.
     * Range: 0.0 (instant) to 0.99 (very smooth), default: 0.5
     */
    val smoothing: Float = 0.5f,
    
    /**
     * Whether wobble is enabled.
     */
    val enabled: Boolean = true
)

/**
 * Target parameters that wobble can modulate.
 */
enum class VoiceWobbleTarget {
    /** Modulate voice volume (VCA level) */
    VOICE_VOLUME,
    
    /** Modulate voice pitch/tune */
    VOICE_PITCH,
    
    /** Modulate FM depth */
    FM_DEPTH,
    
    /** Modulate pair sharpness */
    SHARPNESS,
    
    /** Modulate vibrato amount */
    VIBRATO
}

/**
 * Runtime state for a single voice's wobble.
 */
data class VoiceWobbleState(
    /** Is the pulse currently active (finger down)? */
    val isActive: Boolean = false,
    
    /** Current wobble offset value (-1 to 1 range) */
    val wobbleOffset: Float = 0.0f,
    
    /** Smoothed wobble value for audio application */
    val smoothedWobble: Float = 0.0f,
    
    /** Last pointer position for delta calculation */
    val lastPointerX: Float = 0f,
    val lastPointerY: Float = 0f,
    
    /** Accumulated velocity for wobble calculation */
    val accumulatedVelocity: Float = 0f
)
