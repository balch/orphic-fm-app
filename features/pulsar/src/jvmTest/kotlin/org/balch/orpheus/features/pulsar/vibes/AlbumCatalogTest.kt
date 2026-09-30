package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.VibeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumCatalogTest {

    private fun names(vararg names: String) = names.map(::VibeName)

    private val listed = AlbumCatalog(
        listOf(
            Album.RIF to names("B", "A"),
            Album.STEALTH to names("C"),
        ),
    )

    @Test
    fun aVibeIsOnTheAlbumThatListsIt() {
        assertEquals(Album.RIF, listed.albumOf("A"))
        assertEquals(Album.STEALTH, listed.albumOf("C"))
        assertNull(listed.albumOf("An AI Vibe"))
    }

    @Test
    fun tracksComeInListedOrder() {
        assertEquals(listOf("B", "A"), listed.tracks(Album.RIF))
        assertEquals(emptyList(), listed.tracks(Album.ANOMALIES))
    }

    // ==================== The catch-all album ====================

    @Test
    fun unlistedNamesLandOnTheCatchAllInTheGivenOrderAndListedOnesStay() {
        val catalog = AlbumCatalog(
            listOf(Album.RIF to names("B", "D")),
            catchAll = Album.STEALTH to names("E", "B", "A", "D", "C"),
        )
        assertEquals(listOf("B", "D"), catalog.tracks(Album.RIF))
        assertEquals(listOf("E", "A", "C"), catalog.tracks(Album.STEALTH))
        assertEquals(Album.RIF, catalog.albumOf("D"))
        assertEquals(Album.STEALTH, catalog.albumOf("A"))
        assertEquals(listOf(Album.RIF, Album.STEALTH), catalog.albums, "the catch-all album comes last")
    }

    @Test
    fun aListedCatchAllAlbumKeepsItsPositionAndItsListedTracksFirst() {
        val catalog = AlbumCatalog(
            listOf(Album.STEALTH to names("D"), Album.RIF to names("B")),
            catchAll = Album.STEALTH to names("A", "B", "C", "D"),
        )
        assertEquals(listOf(Album.STEALTH, Album.RIF), catalog.albums)
        assertEquals(listOf("D", "A", "C"), catalog.tracks(Album.STEALTH))
        assertEquals(listOf("B"), catalog.tracks(Album.RIF))
    }

    @Test
    fun aNameOutsideTheCatchAllIsOnNoAlbum() {
        val catalog = AlbumCatalog(
            listOf(Album.RIF to names("B")),
            catchAll = Album.STEALTH to names("A", "B"),
        )
        assertNull(catalog.albumOf("An AI Vibe"))
    }

    // ==================== Guards over the shipped listing ====================

    private val shipped = AlbumCatalog.Default.listing
    private val shippedNames = shipped.flatMap { it.second }

    @Test
    fun everyListedVibeIsACatalogEntry() {
        val unknown = shippedNames.filterNot { it in VibeCatalog.entries }
        assertTrue(
            unknown.isEmpty(),
            "AlbumCatalog.kt lists ${unknown.map { it.value }}, which VibeCatalog.kt does not. Match the name to its " +
                "VibeCatalog.kt entry, or delete the line from AlbumCatalog.kt.",
        )
    }

    @Test
    fun noVibeIsListedTwice() {
        val twice = shippedNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            twice.isEmpty(),
            "AlbumCatalog.kt lists ${twice.map { it.value }} more than once. A vibe is on one album, once: delete the " +
                "extra line from AlbumCatalog.kt.",
        )
    }

    @Test
    fun noAlbumIsListedTwice() {
        val twice = shipped.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            twice.isEmpty(),
            "AlbumCatalog.kt lists the album(s) $twice more than once. Merge their tracks into one entry.",
        )
    }

    // The apps read albums only from AlbumCatalog, so an album set on a Vibe would silently do nothing.
    @Test
    fun noShippedVibeSetsItsAlbum() {
        // STEALTH is Vibe.album's default.
        val setting = VibeCatalogScan.allProviders(subpackages = true).filter { it.vibe.album != Album.STEALTH }.map { it.name.value }
        assertTrue(
            setting.isEmpty(),
            "$setting set `album =` on their Vibe, which the apps ignore. Delete the line from each " +
                "vibe's file (or the vibe it copies); its album comes from AlbumCatalog.kt.",
        )
    }

    // True by construction while STEALTH is the catch-all; kept in case that changes.
    @Test
    fun everyCatalogEntryIsOnAnAlbum() {
        val missing = VibeCatalog.entries.keys.filterNot { it in shippedNames }
        assertTrue(
            missing.isEmpty(),
            "VibeCatalog.kt has ${missing.map { it.value }}, but no album in AlbumCatalog.kt lists them, so the apps " +
                "show them on no album. Add each to its album in AlbumCatalog.kt, at its track position.",
        )
    }
}
