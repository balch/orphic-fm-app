package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixerOpenHoldTest {
    private val collab = MixCollab { 0L }
    private val hold = MixerOpenHold(collab)

    @Test
    fun closedMixerLetsTheSongAdvance() {
        assertFalse(hold.holdsSong())
    }

    @Test
    fun openMixerHoldsTheSong() {
        collab.setMixerVisible(true)
        assertTrue(hold.holdsSong())
    }

    @Test
    fun closingTheMixerReleasesTheHold() {
        collab.setMixerVisible(true)
        collab.setMixerVisible(false)
        assertFalse(hold.holdsSong())
    }
}
