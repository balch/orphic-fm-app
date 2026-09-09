package org.balch.orpheus.djapp.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DjWidgetWireSnapshotTest {

    private val snapshot = DjWidgetSnapshot(
        currentVibe = "Rust Belt",
        albumTitle = "Zero to One",
        nextVibe = "Smokehouse",
        isPlaying = true,
        timerRunning = true,
        timerRemainingSeconds = 1421L,
        timerStatus = "RUNNING",
        artworkPng = byteArrayOf(1, 2, 3),
    )

    @Test
    fun `from copies the rendered fields and records the artwork filename`() {
        val wire = DjWidgetWireSnapshot.from(snapshot, "artwork-rust-belt.png", 1_700_000_000_000L)

        assertEquals("Rust Belt", wire.currentVibe)
        assertEquals("Zero to One", wire.albumTitle)
        assertEquals(true, wire.isPlaying)
        assertEquals(true, wire.timerRunning)
        assertEquals(1421L, wire.timerRemainingSeconds)
        assertEquals("RUNNING", wire.timerStatus)
        assertEquals("artwork-rust-belt.png", wire.artworkFile)
        assertEquals(1_700_000_000_000L, wire.writtenAtEpochMs)
    }

    @Test
    fun `encode never contains artwork bytes`() {
        val json = DjWidgetWire.encode(
            DjWidgetWireSnapshot.from(snapshot, "artwork-rust-belt.png", 1L)
        )
        assertTrue("artworkPng" !in json, "wire JSON must not carry image bytes")
    }

    @Test
    fun `encode then decode round-trips`() {
        val wire = DjWidgetWireSnapshot.from(snapshot, "artwork-rust-belt.png", 42L)
        assertEquals(wire, DjWidgetWire.decode(DjWidgetWire.encode(wire)))
    }

    @Test
    fun `encode then decode round-trips with a null artworkFile`() {
        // The real first-launch state: the widget hits this before any artwork
        // has been written to the App Group container.
        val wire = DjWidgetWireSnapshot.from(snapshot, null, 42L)
        assertEquals(wire, DjWidgetWire.decode(DjWidgetWire.encode(wire)))
    }

    @Test
    fun `decode returns null on malformed json rather than throwing`() {
        assertNull(DjWidgetWire.decode("{ not json"))
    }

    @Test
    fun `artworkSlug lowercases and replaces unsafe characters`() {
        assertEquals("rust-belt", DjWidgetWireSnapshot.artworkSlug("Rust Belt"))
        assertEquals("funk-49-1", DjWidgetWireSnapshot.artworkSlug("Funk 49.1"))
        assertEquals("unknown", DjWidgetWireSnapshot.artworkSlug(""))
    }
}
