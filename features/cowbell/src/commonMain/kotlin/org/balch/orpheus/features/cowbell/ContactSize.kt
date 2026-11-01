package org.balch.orpheus.features.cowbell

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * The contact patch's major axis for [change] in px, or null where the platform does not report
 * one (everything but Android, and a mouse).
 */
expect fun PointerEvent.contactSize(change: PointerInputChange): Float?
