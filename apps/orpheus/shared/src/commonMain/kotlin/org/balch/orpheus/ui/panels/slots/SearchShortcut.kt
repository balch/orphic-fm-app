package org.balch.orpheus.ui.panels.slots

import androidx.compose.ui.input.key.Key

/** Cmd+K on macOS, Ctrl+K elsewhere: opens panel search. */
fun isSearchShortcut(key: Key, isMetaPressed: Boolean, isCtrlPressed: Boolean): Boolean =
    key == Key.K && (isMetaPressed || isCtrlPressed)
