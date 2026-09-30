package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.vibes.AlbumCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumListingTest {
    // Album and track order both run against enum and name order, so a pass proves where they came from.
    private val albums = AlbumCatalog(
        listOf(
            Album.ANOMALIES to listOf(VibeName("Z"), VibeName("X")),
            Album.STEALTH to listOf(VibeName("C"), VibeName("Gone"), VibeName("A")),
            Album.ZERO_TO_ONE to listOf(VibeName("Gone Too")),
            Album.RIF to listOf(VibeName("B")),
        ),
    )

    @Test
    fun albumsAndTracksComeInTheCatalogsOrder() {
        val listing = albumListing(listOf("A", "B", "C", "X", "Z"), albums)
        assertEquals(listOf(Album.ANOMALIES, Album.STEALTH, Album.RIF), listing.keys.toList())
        assertEquals(listOf("Z", "X"), listing[Album.ANOMALIES])
        assertEquals(listOf("C", "A"), listing[Album.STEALTH])
    }

    // A WIP vibe on a live build is listed on its album but must not show there.
    @Test
    fun onlyTheGivenVibesAreListedAndAnAlbumWithNoneIsDropped() {
        assertEquals(mapOf(Album.STEALTH to listOf("A"), Album.RIF to listOf("B")), albumListing(listOf("B", "A"), albums))
    }

    @Test
    fun aVibeOnNoAlbumIsNotListed() =
        assertEquals(mapOf(Album.RIF to listOf("B")), albumListing(listOf("B", "An AI Vibe"), albums))
}
