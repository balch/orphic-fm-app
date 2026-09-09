package org.balch.orpheus.djapp.widget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The Swift extension hand-mirrors this type across a language boundary; drift is
 * invisible until the widget goes blank on a user's phone. Verifies the encoder
 * against the same checked-in fixture Swift's decode test reads.
 *
 * Legitimate shape change: regenerate fixtures/snapshot-golden.json from the
 * encoder's output, then update `DjWidgetWirePayload` in DjWidgetWire.swift.
 */
class DjWidgetGoldenFixtureTest {

    @Test
    fun `wire encoding matches the checked-in Swift fixture`() {
        val fixture = File("../iosApp/DjWidgetExtension/fixtures/snapshot-golden.json")
        if (!fixture.isFile) {
            fail("Golden fixture not found at ${fixture.absolutePath}")
        }
        val golden = fixture.readText().trim()

        val encoded = DjWidgetWire.encode(
            DjWidgetWireSnapshot(
                currentVibe = "Rust Belt",
                albumTitle = "Zero to One",
                isPlaying = true,
                timerRunning = true,
                timerRemainingSeconds = 1421L,
                timerStatus = "RUNNING",
                artworkFile = "artwork-rust-belt.png",
                writtenAtEpochMs = 1_700_000_000_000L,
            )
        )
        assertEquals(golden, encoded)
    }
}
