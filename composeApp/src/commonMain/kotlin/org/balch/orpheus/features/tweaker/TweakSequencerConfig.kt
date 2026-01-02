package org.balch.orpheus.features.tweaker

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * All automatable parameters for the sequencer system.
 * Each parameter has a display label, category, and unique color with alpha.
 */
enum class TweakSequencerParameter(
    val label: String,
    val category: String,
    val color: Color
) {
    // LFO parameters (Cyan spectrum)
    LFO_FREQ_A("Freq A", "LFO", OrpheusColors.neonCyan.copy(alpha = 0.9f)),
    LFO_FREQ_B("Freq B", "LFO", OrpheusColors.neonCyan.copy(alpha = 0.65f)),

    // Delay parameters (Blue spectrum)
    DELAY_TIME_1("Time 1", "Delay", OrpheusColors.electricBlue.copy(alpha = 0.9f)),
    DELAY_TIME_2("Time 2", "Delay", OrpheusColors.electricBlue.copy(alpha = 0.7f)),
    DELAY_MOD_1("Mod 1", "Delay", OrpheusColors.electricBlue.copy(alpha = 0.55f)),
    DELAY_MOD_2("Mod 2", "Delay", OrpheusColors.electricBlue.copy(alpha = 0.45f)),
    DELAY_FEEDBACK("Feedback", "Delay", Color(0xFF4080FF).copy(alpha = 0.8f)),
    DELAY_MIX("Mix", "Delay", Color(0xFF6090FF).copy(alpha = 0.75f)),

    // Volume/Distortion parameters (Orange spectrum)
    DIST_DRIVE("Drive", "Vol", OrpheusColors.warmGlow.copy(alpha = 0.9f)),
    DIST_MIX("Mix", "Vol", OrpheusColors.warmGlow.copy(alpha = 0.65f)),

    // Visualization parameters (Magenta spectrum)
    VIZ_KNOB_1("Knob 1", "Viz", OrpheusColors.neonMagenta.copy(alpha = 0.9f)),
    VIZ_KNOB_2("Knob 2", "Viz", OrpheusColors.neonMagenta.copy(alpha = 0.65f)),

    // Global parameters (Green spectrum)
    GLOB_VIBRATO("Vibrato", "Glob", OrpheusColors.synthGreen.copy(alpha = 0.85f));

    companion object Companion {
        /** Maximum number of parameters that can be automated simultaneously */
        const val MAX_SELECTED = 5

        /** Get all parameters grouped by category */
        fun byCategory(): Map<String, List<TweakSequencerParameter>> =
            entries.groupBy { it.category }
    }
}

/**
 * Playback mode for sequencer automation.
 */
enum class TweakPlaybackMode {
    /** Play sequencer once from start to end, then stop */
    ONCE,

    /** Repeat from start when reaching end */
    LOOP,

    /** Play forward, then backward, then forward, etc. */
    PING_PONG
}

/**
 * Configuration for the parameter sequencer automation.
 *
 * @param durationSeconds Total duration in seconds (10-120 range)
 * @param tweakPlaybackMode How the sequencer loops/repeats
 * @param enabled Whether sequencer automation is active
 * @param selectedParameters List of up to 5 parameters to automate
 */
data class TweakSequencerConfig(
    val durationSeconds: Float = 30f,
    val tweakPlaybackMode: TweakPlaybackMode = TweakPlaybackMode.LOOP,
    val enabled: Boolean = false,
    val selectedParameters: List<TweakSequencerParameter> = emptyList()
) {
    companion object Companion {
        const val MIN_DURATION = 10f
        const val MAX_DURATION = 120f
    }
}
