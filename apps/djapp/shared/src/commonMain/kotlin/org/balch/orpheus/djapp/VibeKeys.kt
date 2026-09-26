package org.balch.orpheus.djapp

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.balch.orpheus.core.playback.SkipDirection

/**
 * ← and → step through vibes. Wire it to the window's onKeyEvent, which runs after the focused
 * element passes on the key: a text field's caret, a knob in adjust mode and the dock's D-pad focus
 * movement all keep their arrows.
 */
fun vibeStepForKey(event: KeyEvent): SkipDirection? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> SkipDirection.PREVIOUS
        Key.DirectionRight -> SkipDirection.NEXT
        else -> null
    }
}

/**
 * [vibeStepForKey] once per press. A held arrow auto-repeats as more KeyDowns, and AWT carries no
 * repeat flag, so each arrow is ignored from its first KeyDown until its KeyUp.
 */
class VibeStepKeys {
    private val held = mutableSetOf<Key>()

    fun stepFor(event: KeyEvent): SkipDirection? {
        if (event.type == KeyEventType.KeyUp) {
            held -= event.key
            return null
        }
        val step = vibeStepForKey(event) ?: return null
        return if (held.add(event.key)) step else null
    }
}
