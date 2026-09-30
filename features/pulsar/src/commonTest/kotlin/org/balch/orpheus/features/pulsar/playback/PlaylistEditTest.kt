package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import org.balch.orpheus.features.pulsar.models.Album
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistEditTest {
    private val catalog = listOf("A", "B", "C", "D")
    private fun edit(prefs: VibePlaylistPrefs?, e: PlaylistEdit, random: Random = Random(7)) =
        applyPlaylistEdit(prefs, catalog, { emptyList() }, e, random)

    @Test
    fun anEditOnNoPrefsStartsFromCatalogOrder() =
        assertEquals(listOf("A", "C", "B", "D"), edit(null, PlaylistEdit.PlaceAfter("C", "A"))?.order)

    @Test
    fun placeAfterMovesDown() =
        assertEquals(listOf("B", "C", "A", "D"), edit(null, PlaylistEdit.PlaceAfter("A", "C"))?.order)

    @Test
    fun placeAfterTheLastEntry() =
        assertEquals(listOf("B", "C", "D", "A"), edit(null, PlaylistEdit.PlaceAfter("A", "D"))?.order)

    @Test
    fun placeBeforeMovesUp() =
        assertEquals(listOf("A", "D", "B", "C"), edit(null, PlaylistEdit.PlaceBefore("D", "B"))?.order)

    // D plays, so Up next reads A, B, C. Dropping A under C must make it the last to come, across the wrap.
    @Test
    fun aDropAcrossTheWrapLandsWhereItWasDropped() {
        val rotation = rotationOf(catalog, edit(null, PlaylistEdit.PlaceAfter("A", "C")))
        val upNext = generateSequence(stepInRotation(rotation, "D", 1)) { stepInRotation(rotation, it, 1) }.take(3).toList()
        assertEquals(listOf("B", "C", "A"), upNext)
    }

    @Test
    fun aMoveOntoItselfOrAnUnknownChangesNothing() {
        assertEquals(catalog, edit(null, PlaylistEdit.PlaceAfter("A", "A"))?.order)
        assertEquals(catalog, edit(null, PlaylistEdit.PlaceAfter("A", "AI Vibe"))?.order)
    }

    @Test
    fun settingAsideAndBackRestoresTheSlot() {
        val aside = edit(null, PlaylistEdit.SetIncluded("B", false))
        assertEquals(setOf("B"), aside?.removed)
        assertEquals(catalog, aside?.order)
        val back = edit(aside, PlaylistEdit.SetIncluded("B", true))
        assertEquals(catalog, back?.order)
        assertEquals(emptySet(), back?.removed)
    }

    @Test
    fun theLastPlayingVibeCannotBeSetAside() {
        val one = VibePlaylistPrefs(catalog, removed = setOf("A", "B", "C"))
        assertEquals(one, edit(one, PlaylistEdit.SetIncluded("D", false)))
    }

    @Test
    fun shuffleIsSeededAndKeepsEveryVibe() {
        val first = edit(null, PlaylistEdit.Shuffle, Random(42))!!
        assertEquals(first, edit(null, PlaylistEdit.Shuffle, Random(42)))
        assertEquals(catalog.sorted(), first.order.sorted())
    }

    @Test
    fun shuffleKeepsWhatIsSetAside() {
        val prefs = VibePlaylistPrefs(catalog, setOf("B"))
        assertEquals(prefs.removed, edit(prefs, PlaylistEdit.Shuffle)?.removed)
    }

    // ==================== QueueAlbum ====================

    // RIF's track order, R3, R2, R1, runs against catalog order; Z is Anomalies' only vibe.
    private val shelf = listOf("R1", "X", "R2", "Y", "R3", "Z")
    private val tracks = mapOf(
        Album.RIF to listOf("R3", "R2", "R1"), Album.STEALTH to listOf("X", "Y"), Album.ANOMALIES to listOf("Z"),
    )
    private val mixed = VibePlaylistPrefs(listOf("X", "R2", "Y", "R1", "Z", "R3"))
    private fun queue(prefs: VibePlaylistPrefs?, now: String, andPick: Boolean = false, album: Album = Album.RIF) =
        applyPlaylistEdit(prefs, shelf, { tracks[it].orEmpty() }, PlaylistEdit.QueueAlbum(album, now, andPick))

    @Test
    fun anAlbumPlaysNextInTrackOrder() =
        assertEquals(listOf("X", "Y", "R3", "R2", "R1", "Z"), queue(mixed, now = "Y")?.order)

    @Test
    fun anAlbumStartsFromCatalogOrderWithNoPrefs() =
        assertEquals(listOf("X", "R3", "R2", "R1", "Y", "Z"), queue(null, now = "X")?.order)

    // R2 plays on; R1 then R3 follow it, wrapping round the album.
    @Test
    fun aPlayingAlbumVibeStaysAndTheRestFollowIt() =
        assertEquals(listOf("X", "R2", "R1", "R3", "Y", "Z"), queue(mixed, now = "R2")?.order)

    // Paused, R3 is about to replace R2, so the album goes in whole where R2 stood.
    @Test
    fun pausedTheWholeAlbumTakesNowsSlot() =
        assertEquals(listOf("X", "R3", "R2", "R1", "Y", "Z"), queue(mixed, now = "R2", andPick = true)?.order)

    // Paused under a vibe from another album, that vibe keeps its place ahead of the album.
    @Test
    fun pausedTheOldNowKeepsItsPlace() =
        assertEquals(listOf("X", "Y", "R3", "R2", "R1", "Z"), queue(mixed, now = "Y", andPick = true)?.order)

    @Test
    fun underAnAiVibeTheAlbumGoesFirst() =
        assertEquals(listOf("R3", "R2", "R1", "X", "Y", "Z"), queue(mixed, now = "AI Vibe")?.order)

    @Test
    fun aSetAsideAlbumComesBack() {
        val out = queue(mixed.copy(removed = setOf("R1", "R2", "R3", "Z")), now = "X")
        assertEquals(listOf("X", "R3", "R2", "R1", "Y", "Z"), out?.order)
        assertEquals(setOf("Z"), out?.removed)
    }

    @Test
    fun anAlbumAlreadyNextStaysPut() {
        val next = VibePlaylistPrefs(listOf("X", "R3", "R2", "R1", "Y", "Z"))
        assertEquals(next, queue(next, now = "X"))
    }

    @Test
    fun aOneVibeAlbumMovesAlone() =
        assertEquals(listOf("X", "Z", "R2", "Y", "R1", "R3"), queue(mixed, now = "X", album = Album.ANOMALIES)?.order)

    @Test
    fun anAlbumWithNoVibesChangesNothing() {
        assertEquals(mixed, queue(mixed, now = "X", album = Album.ZERO_TO_ONE))
        assertNull(queue(null, now = "X", album = Album.ZERO_TO_ONE))
    }

    // A listed vibe the catalog no longer has must not be written into the order.
    @Test
    fun aTrackOutsideTheCatalogIsSkipped() {
        val stale = applyPlaylistEdit(mixed, shelf, { listOf("Gone", "R3") }, PlaylistEdit.QueueAlbum(Album.RIF, "X"))
        assertEquals(listOf("X", "R3", "R2", "Y", "R1", "Z"), stale?.order)
    }

    @Test
    fun resetForgetsEverything() = assertNull(edit(VibePlaylistPrefs(listOf("D", "C", "B", "A")), PlaylistEdit.Reset))

    @Test
    fun anEditMergesNewCatalogVibes() =
        assertEquals(listOf("B", "A", "C", "D"), edit(VibePlaylistPrefs(listOf("B", "A")), PlaylistEdit.SetIncluded("A", true))?.order)
}
