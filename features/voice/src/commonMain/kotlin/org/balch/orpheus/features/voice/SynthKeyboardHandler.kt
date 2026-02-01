package org.balch.orpheus.features.voice

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.balch.orpheus.core.input.KeyboardInputHandler
import org.balch.orpheus.features.drum.DrumFeature

/**
 * Handles keyboard events and dispatches to ViewModels.
 * Returns true if the event was consumed.
 */
object SynthKeyboardHandler {
    fun handleKeyEvent(
        keyEvent: KeyEvent,
        isDialogActive: Boolean,
        voiceFeature: VoicesFeature,
        drumFeature: DrumFeature? = null,
    ): Boolean {

        // Skip keyboard handling when dialog is active
        if (isDialogActive) return false

        val key = keyEvent.key
        val isKeyDown = keyEvent.type == KeyEventType.KeyDown
        val isKeyUp = keyEvent.type == KeyEventType.KeyUp

        // Handle voice trigger keys (A/S/D/F/G/H)
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

        // Handle drum trigger keys (J/K/L)
        if (drumFeature != null) {
            KeyboardInputHandler.getDrumFromKey(key)?.let { drumIndex ->
                if (isKeyDown && !KeyboardInputHandler.isDrumKeyPressed(drumIndex)) { // Prevent auto-repeat triggers
                    KeyboardInputHandler.onDrumKeyDown(drumIndex)
                    when (drumIndex) {
                        0 -> drumFeature.actions.startBdTrigger()
                        1 -> drumFeature.actions.startSdTrigger()
                        2 -> drumFeature.actions.startHhTrigger()
                    }
                    return true
                } else if (isKeyUp) {
                    KeyboardInputHandler.onDrumKeyUp(drumIndex)
                    when (drumIndex) {
                        0 -> drumFeature.actions.stopBdTrigger()
                        1 -> drumFeature.actions.stopSdTrigger()
                        2 -> drumFeature.actions.stopHhTrigger()
                    }
                    return true
                }
            }
        }

        // Handle tune adjustment keys (1-8)
        if (isKeyDown) {
            KeyboardInputHandler.getTuneVoiceFromKey(key)
                ?.let { voiceIndex ->
                    val currentTune = voiceFeature.stateFlow.value.voiceStates[voiceIndex].tune
                    val delta = KeyboardInputHandler.getTuneDelta(keyEvent.isShiftPressed)
                    val newTune = (currentTune + delta).coerceIn(0f, 1f)
                    voiceFeature.actions.onVoiceTuneChange(voiceIndex, newTune)
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
