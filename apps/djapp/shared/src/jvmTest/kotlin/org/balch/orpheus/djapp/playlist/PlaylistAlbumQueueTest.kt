package org.balch.orpheus.djapp.playlist

import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.features.pulsar.playback.applyPlaylistEdit
import org.balch.orpheus.features.pulsar.playback.rotationOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistAlbumQueueTest {
    private val order = listOf("Rust Belt", "Dog House", "Bell Tolls", "Techno Wobble", "Filter Funk")
    private val albums = mapOf(
        Album.STEALTH to listOf("Filter Funk"), Album.RIF to listOf("Bell Tolls"),
        Album.ZERO_TO_ONE to listOf("Dog House"), Album.ANOMALIES to listOf("Rust Belt", "Techno Wobble"),
    )
    // Dog House plays; Up next reads Bell Tolls, Filter Funk, Rust Belt; Techno Wobble is set aside.
    private val prefs = VibePlaylistPrefs(order, removed = setOf("Techno Wobble"))
    private fun viewOf(prefs: VibePlaylistPrefs?) = PlaylistView(rotationOf(order, prefs), albums)

    /** What the feature does with an edit: the pure edit, then the view it leads to. */
    private fun PlaylistScene.land(edit: PlaylistEdit) {
        view = viewOf(applyPlaylistEdit(prefs, order, { albums[it].orEmpty() }, edit))
    }

    @Test
    fun aChipLiftsItsRowsThenQueuesTheAlbumOnce() {
        val sheet = PlaylistScene(viewOf(prefs), "Dog House")
        try {
            assertEquals(listOf("Bell Tolls", "Filter Funk", "Rust Belt"), sheet.upNext(), "sanity")
            sheet.click("Anomalies")
            sheet.idle(96)
            assertEquals(emptyList(), sheet.edits, "the rows lift before anything moves")
            sheet.idle(160)
            assertEquals(listOf<PlaylistEdit>(PlaylistEdit.QueueAlbum(Album.ANOMALIES, "Dog House")), sheet.edits)

            sheet.land(sheet.edits.single())
            sheet.settle()
            assertEquals(listOf("Rust Belt", "Techno Wobble", "Bell Tolls", "Filter Funk"), sheet.upNext())
            assertEquals(emptyList(), sheet.setAside())
            assertEquals(1, sheet.edits.size, "one edit per tap")
        } finally { sheet.close() }
    }

    // One key per vibe in both sections, so a set-aside row travels up into Up next instead of popping in.
    @Test
    fun aSetAsideRowTravelsUpIntoUpNext() {
        val sheet = PlaylistScene(viewOf(prefs), "Dog House")
        try {
            val from = sheet.rowTop("Techno Wobble")
            sheet.click("Anomalies")
            sheet.idle(256)
            sheet.land(sheet.edits.single())
            sheet.idle(64)
            val mid = sheet.rowTop("Techno Wobble")
            sheet.settle()
            val to = sheet.rowTop("Techno Wobble")
            assertTrue(to < from, "sanity: it ends higher ($from -> $to)")
            assertTrue(mid < from && mid > to, "mid-flight at $mid, between $from and $to")
        } finally { sheet.close() }
    }

    // A second chip before the first one's rows have landed: both albums still queue, in tap order.
    @Test
    fun twoQuickTapsQueueBothAlbums() {
        val sheet = PlaylistScene(viewOf(prefs), "Dog House")
        try {
            sheet.click("RIF")
            sheet.idle(48)
            sheet.click("Stealth")
            sheet.settle()
            assertEquals(
                listOf<PlaylistEdit>(
                    PlaylistEdit.QueueAlbum(Album.RIF, "Dog House"),
                    PlaylistEdit.QueueAlbum(Album.STEALTH, "Dog House"),
                ),
                sheet.edits,
            )
        } finally { sheet.close() }
    }

    @Test
    fun closingTheSheetMidLiftStillQueues() {
        val sheet = PlaylistScene(viewOf(prefs), "Dog House")
        sheet.click("RIF")
        sheet.idle(48)
        sheet.close()
        assertEquals(listOf<PlaylistEdit>(PlaylistEdit.QueueAlbum(Album.RIF, "Dog House")), sheet.edits)
    }
}
