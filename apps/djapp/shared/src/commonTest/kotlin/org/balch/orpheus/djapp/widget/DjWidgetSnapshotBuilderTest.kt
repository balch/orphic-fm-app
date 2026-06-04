package org.balch.orpheus.djapp.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DjWidgetSnapshotBuilderTest {

    @Test
    fun `nextVibe advances and wraps`() {
        val names = listOf("A", "B", "C")
        assertEquals("B", DjWidgetSnapshotBuilder.nextVibe("A", names))
        assertEquals("C", DjWidgetSnapshotBuilder.nextVibe("B", names))
        assertEquals("A", DjWidgetSnapshotBuilder.nextVibe("C", names))
    }

    @Test
    fun `nextVibe with single entry returns itself`() {
        assertEquals("A", DjWidgetSnapshotBuilder.nextVibe("A", listOf("A")))
    }

    @Test
    fun `nextVibe with empty list returns dash`() {
        assertEquals("—", DjWidgetSnapshotBuilder.nextVibe("A", emptyList()))
    }

    @Test
    fun `nextVibe with unknown current falls back to first`() {
        assertEquals("A", DjWidgetSnapshotBuilder.nextVibe("X", listOf("A", "B")))
    }

    @Test
    fun `build maps all fields`() {
        val art = byteArrayOf(1, 2, 3)
        val s = DjWidgetSnapshotBuilder.build(
            currentVibe = "Dog House",
            albumTitle = "Stealth",
            vibeNames = listOf("Dog House", "Dust Groove"),
            isPlaying = true,
            timerRunning = true,
            timerRemainingSeconds = 754L,
            timerStatus = "RUNNING",
            artworkPng = art,
        )
        assertEquals("Dog House", s.currentVibe)
        assertEquals("Stealth", s.albumTitle)
        assertEquals("Dust Groove", s.nextVibe)
        assertEquals(true, s.isPlaying)
        assertEquals(true, s.timerRunning)
        assertEquals(754L, s.timerRemainingSeconds)
        assertEquals("RUNNING", s.timerStatus)
        assertSame(art, s.artworkPng)
    }

    @Test
    fun `build coerces negative seconds and blanks empty vibe`() {
        val s = DjWidgetSnapshotBuilder.build(
            currentVibe = "",
            albumTitle = "",
            vibeNames = emptyList(),
            isPlaying = false,
            timerRunning = false,
            timerRemainingSeconds = -5L,
            timerStatus = "IDLE",
            artworkPng = null,
        )
        assertEquals("—", s.currentVibe)
        assertEquals("—", s.nextVibe)
        assertEquals(0L, s.timerRemainingSeconds)
        assertEquals(null, s.artworkPng)
    }
}
