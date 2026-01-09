package org.balch.orpheus.features.voice

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.balch.orpheus.core.input.KeyboardInputHandler

/**
 * Handles keyboard events and dispatches to ViewModels.
 * Returns true if the event was consumed.
 */
object SynthKeyboardHandler {
    fun handleKeyEvent(
        keyEvent: KeyEvent,
        isDialogActive: Boolean,
        voiceFeature: VoicesFeature,
    ): Boolean {

        // Skip keyboard handling when dialog is active
        if (isDialogActive) return false

        val key = keyEvent.key
        val isKeyDown = keyEvent.type == KeyEventType.KeyDown
        val isKeyUp = keyEvent.type == KeyEventType.KeyUp

        // Handle voice trigger keys (A/S/D/F/G/H/J/K)
        KeyboardInputHandler.getVoiceFromKey(key)?.let { voiceIndex ->
            if (isKeyDown && !KeyboardInputHandler.isVoiceKeyPressed(voiceIndex)) {
                KeyboardInputHandler.onVoiceKeyDown(voiceIndex)
                voiceFeature.actions.onPulseStart(voiceIndex)
                return true
            } else if (isKeyUp) {
                KeyboardInputHandler.onVoiceKeyUp(voiceIndex)
                voiceFeature.actions.onPulseEnd(voiceIndex)
                return true
            }
        }

        // Handle tune adjustment keys (1-8)
        if (isKeyDown) {
            KeyboardInputHandler.getTuneVoiceFromKey(key)
                ?.let { voiceIndex ->
                    val currentTune = voiceFeature.stateFlow.value.voiceStates[voiceIndex].tune
                    val delta =KeyboardInputHandler.getTuneDelta(keyEvent.isShiftPressed)
                    val newTune = (currentTune + delta).coerceIn(0f, 1f )
                    voiceFeature.actions.onVoiceTuneChange(voiceIndex,newTune)
                    return true
                }

            // Handle octave shift (Z/X)
            if (KeyboardInputHandler.handleOctaveKey(key)) {
                return true
            }
        }

        return false
    }
}
