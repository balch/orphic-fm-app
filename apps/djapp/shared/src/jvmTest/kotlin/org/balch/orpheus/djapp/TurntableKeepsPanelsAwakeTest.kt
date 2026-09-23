package org.balch.orpheus.djapp

import org.balch.orpheus.features.dj.DjUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A deck fader left up means the user is mixing, so the idle fade must stand down. */
class TurntableKeepsPanelsAwakeTest {

    @Test
    fun `either deck above the floor counts as up`() {
        assertFalse(anyTurntableUp(DjUiState()))
        assertTrue(anyTurntableUp(DjUiState(wetA = 0.01f)))
        assertTrue(anyTurntableUp(DjUiState(wetB = 0.01f)))
    }

    @Test
    fun `a turntable up stops the fade that would otherwise run`() {
        assertTrue(fadesPanelsWhenIdle(DjLayout.Portrait, vizOptsIn = true, sheetOpen = false, turntableUp = false))
        assertFalse(fadesPanelsWhenIdle(DjLayout.Portrait, vizOptsIn = true, sheetOpen = false, turntableUp = true))
    }
}
