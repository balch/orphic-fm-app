package org.balch.orpheus.core.input

/**
 * Actions that can be bound to keyboard keys.
 *
 * The sealed hierarchy lets [SynthKeyboardHandler][org.balch.orpheus.features.voice.SynthKeyboardHandler]
 * be a pure dispatcher with zero feature-specific knowledge.
 *
 * Not data classes: lambdas use reference identity for equality,
 * which would make data-class equals/hashCode misleading.
 */
sealed interface KeyAction {
    /** Gate-style: fires [onDown] on press, [onUp] on release. Auto-repeat protected by [id]. */
    class Gate(val id: Int, val onDown: () -> Unit, val onUp: () -> Unit) : KeyAction {
        override fun equals(other: Any?) = other is Gate && other.id == id
        override fun hashCode() = id
    }

    /** Fires once per key event. Receives shift state for modifier-aware actions.
     *  Returns true if the event was consumed, false to let it propagate. */
    class Trigger(val onDown: (isShiftPressed: Boolean) -> Boolean) : KeyAction
}
