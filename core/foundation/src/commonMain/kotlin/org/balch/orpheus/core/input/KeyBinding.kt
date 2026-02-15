package org.balch.orpheus.core.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/**
 * Describes a single keyboard shortcut binding for a synth feature.
 *
 * Each feature's [SynthFeature.keyBindings][org.balch.orpheus.core.SynthFeature.keyBindings]
 * list provides these with a non-null [action] wired to the ViewModel.
 * AI tools and documentation consumers read the same list, ignoring [action].
 */
data class KeyBinding(
    val key: Key,
    val label: String,
    val description: String,
    val action: KeyAction? = null,
    val requiresShift: Boolean = false,
    val eventType: KeyEventType = KeyEventType.KeyDown,
)
