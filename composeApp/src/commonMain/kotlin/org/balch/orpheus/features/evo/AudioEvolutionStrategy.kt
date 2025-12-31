package org.balch.orpheus.features.evo

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.core.audio.SynthEngine

/**
 * Interface for pluggable audio evolution strategies.
 * Implementations should be injected into the set of available strategies.
 *
 * Follows the same pattern as Visualization for consistency.
 */
interface AudioEvolutionStrategy {
    /** Unique identifier for this strategy */
    val id: String

    /** Display name for UI */
    val name: String

    /** Theme color for this strategy */
    val color: Color

    /** Label for the first knob (strategy-specific parameter) */
    val knob1Label: String

    /** Label for the second knob (strategy-specific parameter) */
    val knob2Label: String

    /** Set the first knob value (0-1) */
    fun setKnob1(value: Float)

    /** Set the second knob value (0-1) */
    fun setKnob2(value: Float)

    /**
     * Perform one step of evolution.
     * Called repeatedly by the ViewModel's evolution loop.
     * @param engine The synth engine to control.
     */
    suspend fun evolve(engine: SynthEngine)

    /** Called when this strategy is activated */
    fun onActivate()

    /** Called when this strategy is deactivated */
    fun onDeactivate()
}