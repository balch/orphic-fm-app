package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LickKeyMapTest {
    @Test
    fun `in key rows`() {
        assertEquals(0, LickKeyMap.offsetFor(Key.A, KeyMode.InKey))
        assertEquals(9, LickKeyMap.offsetFor(Key.Semicolon, KeyMode.InKey))
        assertEquals(10, LickKeyMap.offsetFor(Key.Apostrophe, KeyMode.InKey))
        assertEquals(7, LickKeyMap.offsetFor(Key.Q, KeyMode.InKey))
        assertEquals(16, LickKeyMap.offsetFor(Key.P, KeyMode.InKey))
        assertEquals(-7, LickKeyMap.offsetFor(Key.Z, KeyMode.InKey))
        assertEquals(-1, LickKeyMap.offsetFor(Key.M, KeyMode.InKey))
    }

    @Test
    fun `chromatic piano layout`() {
        assertEquals(0, LickKeyMap.offsetFor(Key.A, KeyMode.Chromatic))
        assertEquals(1, LickKeyMap.offsetFor(Key.W, KeyMode.Chromatic))
        assertEquals(2, LickKeyMap.offsetFor(Key.S, KeyMode.Chromatic))
        assertEquals(17, LickKeyMap.offsetFor(Key.Apostrophe, KeyMode.Chromatic))
    }

    @Test
    fun `unmapped keys are null`() {
        for (mode in KeyMode.entries) {
            assertNull(LickKeyMap.offsetFor(Key.DirectionUp, mode))
            assertNull(LickKeyMap.offsetFor(Key.Enter, mode))
            assertNull(LickKeyMap.offsetFor(Key.One, mode))
        }
        assertNull(LickKeyMap.offsetFor(Key.Z, KeyMode.Chromatic))
    }
}
