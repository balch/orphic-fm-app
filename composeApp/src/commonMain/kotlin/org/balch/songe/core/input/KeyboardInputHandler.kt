package org.balch.songe.core.input

import androidx.compose.ui.input.key.Key

/**
 * Keyboard input handler for the synthesizer.
 * Maps QWERTY middle row to voice triggers and number row to tune adjustments.
 */
object KeyboardInputHandler {
    
    // Middle row keys → Voice triggers (0-7)
    private val voiceKeyMap: Map<Key, Int> = mapOf(
        Key.A to 0,  // Voice 1
        Key.S to 1,  // Voice 2
        Key.D to 2,  // Voice 3
        Key.F to 3,  // Voice 4
        Key.G to 4,  // Voice 5
        Key.H to 5,  // Voice 6
        Key.J to 6,  // Voice 7
        Key.K to 7   // Voice 8
    )
    
    // Number row keys → Tune adjustments for voices (0-7)
    private val tuneKeyMap: Map<Key, Int> = mapOf(
        Key.One to 0,
        Key.Two to 1,
        Key.Three to 2,
        Key.Four to 3,
        Key.Five to 4,
        Key.Six to 5,
        Key.Seven to 6,
        Key.Eight to 7
    )
    
    // Track which keys are currently pressed (for trigger behavior)
    private val pressedVoiceKeys = mutableSetOf<Int>()
    private val tuneDelta = 0.05f // How much to adjust tune per key press
    
    // Octave shift state
    var octaveShift: Int = 0
        private set
    
    /**
     * Returns the voice index (0-7) if this key triggers a voice, null otherwise.
     */
    fun getVoiceFromKey(key: Key): Int? = voiceKeyMap[key]
    
    /**
     * Returns the voice index (0-7) if this key adjusts a voice's tune, null otherwise.
     */
    fun getTuneVoiceFromKey(key: Key): Int? = tuneKeyMap[key]
    
    /**
     * Check if a voice key is currently pressed.
     */
    fun isVoiceKeyPressed(voiceIndex: Int): Boolean = voiceIndex in pressedVoiceKeys
    
    /**
     * Mark a voice key as pressed.
     */
    fun onVoiceKeyDown(voiceIndex: Int) {
        pressedVoiceKeys.add(voiceIndex)
    }
    
    /**
     * Mark a voice key as released.
     */
    fun onVoiceKeyUp(voiceIndex: Int) {
        pressedVoiceKeys.remove(voiceIndex)
    }
    
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
     * Shift = coarse adjustment, no shift = fine adjustment
     */
    fun getTuneDelta(isShiftPressed: Boolean): Float {
        return if (isShiftPressed) tuneDelta * 4f else tuneDelta
    }
    
    /**
     * Reset all state.
     */
    fun reset() {
        pressedVoiceKeys.clear()
        octaveShift = 0
    }
}
