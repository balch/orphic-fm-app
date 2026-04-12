package org.balch.orpheus.core.media

/**
 * Represents the current playback mode for display in MediaSession and notifications.
 */
enum class PlaybackMode(val displayName: String) {
    /** User is playing manually (no AI active) */
    USER("Manual Play"),
    
    /** AI Drone mode is active */
    DRONE("AI Drone"),
    
    /** AI Solo mode is active */
    SOLO("AI Solo"),
    
    /** REPL/Tidal code generation mode is active */
    REPL("Live Code"),

    /** Pulsar beat machine is active */
    PULSAR("Pulsar")
}

/**
 * Metadata for the MediaSession display.
 */
data class PlaybackMetadata(
    val title: String = "Orpheus Synthesizer",
    val mode: PlaybackMode = PlaybackMode.USER,
    val isPlaying: Boolean = true,
    val subtitle: String? = null,
) {
    /**
     * The subtitle to display based on the current mode.
     * Uses custom subtitle if provided, otherwise generates from mode.
     * Format: "Playing: AI Drone" or "Paused: Live Code"
     */
    val displaySubtitle: String
        get() = subtitle ?: if (isPlaying) "Playing: ${mode.displayName}" else "Paused: ${mode.displayName}"
}
