package org.balch.orpheus.ui.panels.slots

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlotMetricsTest {

    @Test
    fun capacityCountsWholeUnitsAndNeverDropsBelowOne() {
        assertEquals(2, slotCapacity(850.dp))
        assertEquals(4, slotCapacity(1280.dp))
        assertEquals(5, slotCapacity(1900.dp))
        assertEquals(1, slotCapacity(600.dp))
        assertEquals(1, slotCapacity(100.dp))
    }

    @Test
    fun scaleIsClampedBothWays() {
        // Width and height both have headroom, so the 1.25 ceiling applies.
        assertEquals(1.25f, slotScale(425.dp, 430.dp, 1))
        assertEquals(0.85f, slotScale(320.dp, 200.dp, 1))
    }

    @Test
    fun scaleFollowsTheTighterAxis() {
        assertEquals(1f, slotScale(320.dp, 330.dp, 1))
        assertEquals(1f, slotScale(640.dp, 330.dp, 2))
        // Wide enough for 1.33 but the height only allows 1.0.
        assertEquals(1f, slotScale(425.dp, 330.dp, 1))
    }
}
