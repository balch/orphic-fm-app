package org.balch.orpheus.ui.panels.slots

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchShortcutTest {

    @Test
    fun kWithMetaOrCtrlOpensSearch() {
        assertTrue(isSearchShortcut(Key.K, isMetaPressed = true, isCtrlPressed = false))
        assertTrue(isSearchShortcut(Key.K, isMetaPressed = false, isCtrlPressed = true))
    }

    @Test
    fun plainKAndOtherKeysAreNotTheShortcut() {
        assertFalse(isSearchShortcut(Key.K, isMetaPressed = false, isCtrlPressed = false))
        assertFalse(isSearchShortcut(Key.J, isMetaPressed = true, isCtrlPressed = false))
    }
}
