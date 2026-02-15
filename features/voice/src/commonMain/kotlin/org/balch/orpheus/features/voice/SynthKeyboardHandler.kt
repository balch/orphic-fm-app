package org.balch.orpheus.features.voice

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.balch.orpheus.core.input.KeyAction
import org.balch.orpheus.core.input.KeyBinding

/**
 * Pure keyboard dispatcher: maps key events to [KeyAction]s.
 * Has zero feature-specific knowledge — all wiring lives in the action map
 * built by `rememberSynthKeyActions()`.
 *
 * Respects [KeyBinding.requiresShift] (shift-specific bindings take priority)
 * and [KeyBinding.eventType] (Trigger actions only fire on the matching event type).
 */
object SynthKeyboardHandler {

    // Gate press tracking: prevents auto-repeat from re-triggering gates
    private val pressedGates = mutableSetOf<Int>()

    fun handleKeyEvent(
        keyEvent: KeyEvent,
        isDialogActive: Boolean,
        keyActions: Map<Key, List<KeyBinding>>,
    ): Boolean {
        if (isDialogActive) return false

        val key = keyEvent.key
        val isKeyDown = keyEvent.type == KeyEventType.KeyDown
        val isKeyUp = keyEvent.type == KeyEventType.KeyUp

        val bindings = keyActions[key] ?: return false

        // Find best matching binding: prefer shift-specific when shift is pressed,
        // fall through to non-shift binding otherwise (e.g. gates that don't care about shift).
        val binding = if (keyEvent.isShiftPressed) {
            bindings.firstOrNull { it.requiresShift } ?: bindings.firstOrNull { !it.requiresShift }
        } else {
            bindings.firstOrNull { !it.requiresShift }
        } ?: return false

        val action = binding.action ?: return false

        return when (action) {
            is KeyAction.Gate -> {
                // Gates always handle both KeyDown and KeyUp regardless of eventType
                if (isKeyDown && action.id !in pressedGates) {
                    pressedGates.add(action.id)
                    action.onDown()
                    true
                } else if (isKeyUp) {
                    pressedGates.remove(action.id)
                    action.onUp()
                    true
                } else {
                    // Auto-repeat key down — suppress
                    isKeyDown
                }
            }
            is KeyAction.Trigger -> {
                // Triggers fire only on the event type specified by the binding
                if (keyEvent.type == binding.eventType) {
                    action.onDown(keyEvent.isShiftPressed)
                } else {
                    false
                }
            }
        }
    }
}
