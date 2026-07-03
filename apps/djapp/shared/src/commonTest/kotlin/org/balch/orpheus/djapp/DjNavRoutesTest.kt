package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DjNavRoutesTest {

    @Test
    fun aiTabOpensAsSheet() {
        assertTrue(AiTab.opensAsSheet, "AiTab must open as an overlay sheet, not an in-place destination")
    }

    @Test
    fun destinationTabsDoNotOpenAsSheet() {
        assertFalse(DjTab.opensAsSheet)
        assertFalse(MixTab.opensAsSheet)
        assertFalse(TimerTab.opensAsSheet)
        assertFalse(HornTab.opensAsSheet)
    }
}
