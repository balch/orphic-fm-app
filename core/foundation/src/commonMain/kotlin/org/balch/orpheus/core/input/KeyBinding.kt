package org.balch.orpheus.core.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/**
 * Describes a single keyboard shortcut binding for a synth feature.
 *
 * Static bindings in [SynthControl.keyboardControlKeys][org.balch.orpheus.core.SynthFeature.SynthControl.keyboardControlKeys]
 * leave [action] null — they serve as documentation for AI agents.
 * Instance-level bindings in [SynthFeature.keyBindings][org.balch.orpheus.core.SynthFeature.keyBindings]
 * provide a non-null [action] wired to the ViewModel.
 */
data class KeyBinding(
    val key: Key,
    val label: String,
    val description: String,
    val action: KeyAction? = null,
    val requiresShift: Boolean = false,
    val eventType: KeyEventType = KeyEventType.KeyDown,
)
