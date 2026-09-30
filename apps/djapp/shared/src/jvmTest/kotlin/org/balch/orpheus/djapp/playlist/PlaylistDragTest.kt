package org.balch.orpheus.djapp.playlist

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.features.pulsar.playback.VibeRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistDragTest {
    private val order = listOf("Rust Belt", "Dog House", "Bell Tolls", "Techno Wobble", "Filter Funk")
    // Dog House plays, so Up next reads Bell Tolls, Techno Wobble, Filter Funk, Rust Belt.
    private val view = PlaylistView(
        rotation = VibeRotation(order, listOf("Rust Belt", "Bell Tolls", "Techno Wobble", "Filter Funk")),
        albums = mapOf(Album.RIF to order),
    )

    private fun PlaylistScene.drag(handle: String, dy: Float, steps: Int = 30) {
        val from = handleOf(handle)
        pointer(PointerEventType.Press, from)
        idle(32)
        for (i in 1..steps) { pointer(PointerEventType.Move, from + Offset(0f, dy * i / steps)); idle(16) }
        idle(200)
    }

    @Test
    fun aDragMovesRowsLiveAndEditsOnceOnDrop() {
        val sheet = PlaylistScene(view, "Dog House")
        try {
            val from = sheet.handleOf("Bell Tolls")
            val dragged = listOf("Techno Wobble", "Filter Funk", "Bell Tolls", "Rust Belt")
            sheet.drag("Bell Tolls", 120f)
            assertEquals(dragged, sheet.upNext())
            assertEquals(emptyList(), sheet.edits, "nothing is edited until the drop")

            sheet.pointer(PointerEventType.Release, from + Offset(0f, 120f))
            assertEquals(listOf<PlaylistEdit>(PlaylistEdit.PlaceAfter("Bell Tolls", "Filter Funk")), sheet.edits)
            // The edit reaches the view a beat later; until then the rows must not snap back.
            repeat(25) { sheet.idle(16); assertEquals(dragged, sheet.upNext()) }

            val edited = view.copy(
                rotation = VibeRotation(
                    listOf("Rust Belt", "Dog House", "Techno Wobble", "Filter Funk", "Bell Tolls"),
                    listOf("Rust Belt", "Techno Wobble", "Filter Funk", "Bell Tolls"),
                ),
            )
            sheet.view = edited
            sheet.idle(64)
            assertEquals(playlistSections(edited, "Dog House").upNext, sheet.upNext())

            // The landed drag must not hold the rows: the old order coming back shows as itself.
            sheet.view = view
            sheet.settle()
            assertEquals(playlistSections(view, "Dog House").upNext, sheet.upNext())
        } finally { sheet.close() }
    }

    // Even a same-slot PlaceAfter could pull the row past hidden set-aside slots.
    @Test
    fun aDropWhereTheRowStartedEditsNothing() {
        val sheet = PlaylistScene(view, "Dog House")
        try {
            val from = sheet.handleOf("Bell Tolls")
            sheet.drag("Bell Tolls", 120f)
            assertEquals(listOf("Techno Wobble", "Filter Funk", "Bell Tolls", "Rust Belt"), sheet.upNext(), "sanity: it moved")
            for (i in 1..30) { sheet.pointer(PointerEventType.Move, from + Offset(0f, 120f - 4f * i)); sheet.idle(16) }
            sheet.idle(200)
            sheet.pointer(PointerEventType.Release, from)
            sheet.settle()
            assertEquals(emptyList(), sheet.edits)
            assertEquals(playlistSections(view, "Dog House").upNext, sheet.upNext())
        } finally { sheet.close() }
    }

    // A second finger sets Filter Funk aside while the first still holds Bell Tolls: the held rows keep it,
    // so Set aside must not key it a second time (a LazyColumn throws on a repeated key).
    @Test
    fun aVibeSetAsideMidDragIsKeyedOnce() {
        val sheet = PlaylistScene(view, "Dog House")
        try {
            val from = sheet.handleOf("Bell Tolls")
            val dragged = listOf("Techno Wobble", "Filter Funk", "Bell Tolls", "Rust Belt")
            sheet.drag("Bell Tolls", 120f)
            assertEquals(dragged, sheet.upNext(), "sanity: it moved")

            val aside = view.copy(rotation = VibeRotation(order, listOf("Rust Belt", "Bell Tolls", "Techno Wobble")))
            sheet.view = aside
            sheet.idle(160)
            assertEquals(dragged, sheet.upNext(), "the rows hold under the finger")
            assertEquals(emptyList(), sheet.setAside())
            assertTrue(sheet.shows("UP NEXT · 4") && sheet.shows("SET ASIDE · 0"), "the counts match the rows shown")

            sheet.pointer(PointerEventType.Release, from + Offset(0f, 120f))
            sheet.settle()
            assertEquals(playlistSections(aside, "Dog House").upNext, sheet.upNext())
            assertEquals(listOf("Filter Funk"), sheet.setAside())
            assertTrue(sheet.shows("UP NEXT · 3") && sheet.shows("SET ASIDE · 1"))
        } finally { sheet.close() }
    }

    @Test
    fun aDropCatchesUpWhenTheSongAdvancedMidDrag() {
        val sheet = PlaylistScene(view, "Dog House")
        try {
            val from = sheet.handleOf("Bell Tolls")
            sheet.drag("Bell Tolls", 120f)
            // Bell Tolls starts playing mid-drag: the rows stay frozen under the finger.
            sheet.current = "Bell Tolls"
            sheet.idle(64)
            assertEquals(listOf("Techno Wobble", "Filter Funk", "Bell Tolls", "Rust Belt"), sheet.upNext())

            // The row goes back where it started, so the drop changes nothing in the rotation.
            for (i in 1..30) { sheet.pointer(PointerEventType.Move, from + Offset(0f, 120f - 4f * i)); sheet.idle(16) }
            sheet.idle(200)
            sheet.pointer(PointerEventType.Release, from)
            sheet.settle()
            assertEquals(playlistSections(view, "Bell Tolls").upNext, sheet.upNext())
        } finally { sheet.close() }
    }
}
