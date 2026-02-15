package org.balch.orpheus.core.input

import androidx.compose.ui.input.key.Key

/**
 * Keyboard state for the synthesizer.
 * Tracks octave shift and provides tune-delta calculation.
 * Pressed-key tracking lives in SynthKeyboardHandler (gate dispatch).
 */
object KeyboardInputHandler {

    private const val TUNE_DELTA = 0.05f

    // Octave shift state
    var octaveShift: Int = 0
        private set

    /**
     * Handle octave shift keys (Z = down, X = up).
     * Returns true if the key was an octave key.
     */
    fun handleOctaveKey(key: Key): Boolean {
        return when (key) {
            Key.Z -> {
                if (octaveShift > -2) octaveShift--
                true
            }
            Key.X -> {
                if (octaveShift < 2) octaveShift++
                true
            }
            else -> false
        }
    }

    /**
     * Get the tune delta based on shift key state.
     * Shift = coarse adjustment, no shift = fine adjustment.
     */
    fun getTuneDelta(isShiftPressed: Boolean): Float {
        return if (isShiftPressed) TUNE_DELTA * 4f else TUNE_DELTA
    }

    fun reset() {
        octaveShift = 0
    }
}
