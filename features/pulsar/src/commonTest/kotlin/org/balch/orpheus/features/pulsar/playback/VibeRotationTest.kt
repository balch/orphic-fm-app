package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VibeRotationTest {
    private val catalog = listOf("A", "B", "C", "D")
    private fun rotation(prefs: VibePlaylistPrefs?) = rotationOf(catalog, prefs)

    @Test
    fun noPrefsIsCatalogOrder() = assertEquals(VibeRotation(catalog, catalog), rotation(null))

    @Test
    fun theSavedOrderWins() =
        assertEquals(listOf("C", "A", "B", "D"), rotation(VibePlaylistPrefs(listOf("C", "A", "B", "D"))).order)

    @Test
    fun unknownNamesDropAndNewOnesJoinAtTheEnd() =
        assertEquals(listOf("B", "A", "C", "D"), rotation(VibePlaylistPrefs(listOf("Gone", "B", "A"))).order)

    @Test
    fun duplicatesCollapse() =
        assertEquals(listOf("B", "A", "C", "D"), rotation(VibePlaylistPrefs(listOf("B", "A", "B"))).order)

    @Test
    fun removedVibesKeepTheirSlotButDoNotPlay() {
        val r = rotation(VibePlaylistPrefs(catalog, removed = setOf("B")))
        assertEquals(catalog, r.order)
        assertEquals(listOf("A", "C", "D"), r.playing)
    }

    @Test
    fun removingEverythingPlaysTheWholeOrder() =
        assertEquals(catalog, rotation(VibePlaylistPrefs(catalog, removed = catalog.toSet())).playing)

    // ==================== stepInRotation ====================

    private val r = VibeRotation(order = listOf("A", "B", "C", "D", "E"), playing = listOf("A", "C", "E"))

    @Test
    fun stepsWithinPlaying() {
        assertEquals("E", stepInRotation(r, "C", 1))
        assertEquals("A", stepInRotation(r, "C", -1))
    }

    @Test
    fun wrapsBothWays() {
        assertEquals("A", stepInRotation(r, "E", 1))
        assertEquals("E", stepInRotation(r, "A", -1))
    }

    @Test
    fun aSetAsideVibeStepsFromItsSlot() {
        assertEquals("C", stepInRotation(r, "B", 1))
        assertEquals("A", stepInRotation(r, "B", -1))
    }

    @Test
    fun aSetAsideVibeWrapsFromItsSlot() {
        val tail = VibeRotation(listOf("A", "B", "C", "D"), listOf("B", "C"))
        assertEquals("B", stepInRotation(tail, "D", 1))
        assertEquals("C", stepInRotation(tail, "A", -1))
    }

    @Test
    fun anUnknownVibeGoesToTheEnds() {
        assertEquals("A", stepInRotation(r, "AI Vibe", 1))
        assertEquals("E", stepInRotation(r, "AI Vibe", -1))
    }

    @Test
    fun aLoneVibeStepsToItself() =
        assertEquals("A", stepInRotation(VibeRotation(listOf("A", "B"), listOf("A")), "A", 1))

    @Test
    fun nothingPlayingHasNoStep() = assertNull(stepInRotation(VibeRotation.EMPTY, "A", 1))
}
