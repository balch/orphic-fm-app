package org.balch.orpheus.features.timeline

/**
 * Configuration for a duo timeline automation.
 *
 * @param parameterName Display name for this timeline (e.g., "LFO")
 * @param paramALabel Label for first parameter (e.g., "Freq A")
 * @param paramBLabel Label for second parameter (e.g., "Freq B")
 * @param durationSeconds Total duration in seconds (10-120 range)
 * @param playbackMode How the timeline loops/repeats
 * @param enabled Whether timeline automation is active
 */
data class DuoTimelineConfig(
    val parameterName: String = "Timeline",
    val paramALabel: String = "Param A",
    val paramBLabel: String = "Param B",
    val durationSeconds: Float = 30f,
    val playbackMode: PlaybackMode = PlaybackMode.ONCE,
    val enabled: Boolean = false
) {
    companion object {
        const val MIN_DURATION = 10f
        const val MAX_DURATION = 120f

        /**
         * Default configuration for LFO frequency automation.
         */
        val LFO = DuoTimelineConfig(
            parameterName = "LFO",
            paramALabel = "Freq A",
            paramBLabel = "Freq B"
        )

        /**
         * Default configuration for Delay time automation.
         */
        val DELAY = DuoTimelineConfig(
            parameterName = "Delay",
            paramALabel = "Time 1",
            paramBLabel = "Time 2"
        )
    }
}

/**
 * Playback mode for timeline automation.
 */
enum class PlaybackMode {
    /** Play timeline once from start to end, then stop */
    ONCE,

    /** Repeat from start when reaching end */
    LOOP,

    /** Play forward, then backward, then forward, etc. */
    PING_PONG
}

/**
 * Target parameter pair for timeline automation.
 */
enum class TimelineTarget {
    /** Controls Hyper LFO Freq A and Freq B */
    LFO,

    /** Controls Delay Time 1 and Time 2 */
    DELAY;

    /**
     * Get the default configuration for this target.
     */
    fun defaultConfig(): DuoTimelineConfig = when (this) {
        LFO -> DuoTimelineConfig.LFO
        DELAY -> DuoTimelineConfig.DELAY
    }
}
