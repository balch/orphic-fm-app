package org.balch.orpheus.features.pulsar.kraken

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KrakenGestureTest {
    private var clock = 0L
    private val gesture = KrakenGesture(now = { clock })

    @Test
    fun aHoldEngagesUntilRelease() {
        gesture.press()
        assertTrue(gesture.engaged)
        assertEquals(1, gesture.presses)
        clock += 900
        gesture.release()
        assertFalse(gesture.engaged)
    }

    @Test
    fun aPressWhileHeldIsIgnored() {
        gesture.press()
        gesture.press()
        assertEquals(1, gesture.presses)
        assertFalse(gesture.latched)
        gesture.release()
        assertFalse(gesture.engaged)
    }

    @Test
    fun aQuickSecondTapLatches() {
        gesture.press(); clock += 80; gesture.release()
        clock += 150
        gesture.press(); clock += 80; gesture.release()
        assertTrue(gesture.latched)
        assertTrue(gesture.engaged)
        assertEquals(2, gesture.presses)
    }

    @Test
    fun aTapWhileLatchedLetsGoWithoutAStab() {
        gesture.press(); gesture.release(); clock += 100
        gesture.press(); gesture.release()
        clock += 2_000
        gesture.press()
        assertFalse(gesture.engaged, "the unlatching press must not engage")
        assertEquals(2, gesture.presses, "the unlatching press sends no stab")
        gesture.release()
        assertFalse(gesture.latched)
    }

    @Test
    fun aSlowSecondTapDoesNotLatch() {
        gesture.press(); gesture.release()
        clock += KrakenGesture.DOUBLE_TAP_MS + 1
        gesture.press(); gesture.release()
        assertFalse(gesture.latched)
    }

    @Test
    fun aTapRightAfterUnlatchingDoesNotRelatch() {
        gesture.press(); gesture.release(); clock += 100
        gesture.press(); gesture.release()           // latched
        clock += 1_000
        gesture.press(); gesture.release()           // unlatch
        clock += 100
        gesture.press(); gesture.release()           // a fresh stab, not a latch
        assertFalse(gesture.latched)
    }

    @Test
    fun resetClearsEverythingButTheCount() {
        gesture.press(); gesture.release(); clock += 100; gesture.press()
        gesture.reset()
        assertFalse(gesture.engaged)
        assertFalse(gesture.latched)
        assertEquals(2, gesture.presses, "the engine edge-detects the count, so it never rewinds")
    }
}
