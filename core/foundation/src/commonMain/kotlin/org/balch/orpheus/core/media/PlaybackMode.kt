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

    /** Audio evolution engine is running */
    EVO("Audio Evo"),

    /** Pulsar beat machine is active */
    PULSAR("Pulsar")
}

/**
 * Metadata for the MediaSession display. Producers feed this through
 * PlaybackController; consumers (platform actuals) display title + subtitle +
 * artwork. Subtitle is always supplied by the producer/controller — there is
 * no mode-based fallback (the controller composes the right value from
 * MetadataProducer.subtitleFlow + OverlaySubtitleProducer.overlayFlow before
 * constructing this).
 */
data class PlaybackMetadata(
    val title: String = "Orpheus",
    val isPlaying: Boolean = true,
    val subtitle: String = "",
    /**
     * PNG bytes for the now-playing artwork shown by the OS (macOS Control
     * Center, Auto, lock screen, etc.). Null = no artwork update. Compared
     * by reference for distinctUntilChanged so producers should cache and
     * re-emit the same array when the title hasn't changed.
     */
    val artworkPng: ByteArray? = null,
)
