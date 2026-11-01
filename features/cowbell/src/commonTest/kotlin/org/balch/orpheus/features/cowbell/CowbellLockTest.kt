package org.balch.orpheus.features.cowbell

import kotlin.test.Test
import kotlin.test.assertEquals

class CowbellLockTest {

    @Test
    fun lockZeroTurnsGridOff() {
        assertEquals(false to null, lockToGrid(0f))
    }

    @Test
    fun lockRaisesStrengthFromFloor() {
        assertEquals(0.575f, lockToGrid(0.5f).second!!, 1e-4f)
        assertEquals(1f, lockToGrid(1f).second!!, 1e-4f)
    }

    @Test
    fun lockFollowsPortsSetElsewhere() {
        assertEquals(0.5f, lockFrom(true, 0.575f), 1e-4f)
        assertEquals(0f, lockFrom(false, 0.9f), 1e-6f)
        assertEquals(0f, lockFrom(true, 0.1f), 1e-6f)
    }

    @Test
    fun lockRoundTrips() {
        for (i in 1..20) {
            val lock = i * 0.05f
            assertEquals(lock, lockFrom(true, lockToGrid(lock).second!!), 1e-4f)
        }
    }
}
