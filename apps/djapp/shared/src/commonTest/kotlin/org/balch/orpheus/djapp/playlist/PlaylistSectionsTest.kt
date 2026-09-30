package org.balch.orpheus.djapp.playlist

import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.features.pulsar.playback.VibeRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistSectionsTest {
    private val order = listOf("A", "B", "C", "D", "E")
    private fun view(playing: List<String>) = PlaylistView(VibeRotation(order, playing))

    @Test
    fun upNextStartsAfterNowAndLeavesItOut() {
        val s = playlistSections(view(order), "C")
        assertEquals(listOf("D", "E", "A", "B"), s.upNext)
        assertEquals(emptyList(), s.setAside)
    }

    @Test
    fun aSetAsideNowStillLeadsAndIsInNeitherList() {
        val s = playlistSections(view(listOf("A", "C", "E")), "B")
        assertEquals("B", s.now)
        assertEquals(listOf("C", "E", "A"), s.upNext)
        assertEquals(listOf("D"), s.setAside)
    }

    @Test
    fun everyVibeAppearsExactlyOnce() {
        val s = playlistSections(view(listOf("A", "C")), "E")
        assertEquals(order.sorted(), (listOf(s.now) + s.upNext + s.setAside).sorted())
    }

    @Test
    fun setAsideKeepsTheSavedOrder() =
        assertEquals(listOf("B", "D"), playlistSections(view(listOf("A", "C", "E")), "A").setAside)

    @Test
    fun anAiVibeLeadsAndEveryoneIsUpNext() {
        val s = playlistSections(view(order), "AI Vibe")
        assertEquals("AI Vibe", s.now)
        assertEquals(order, s.upNext)
    }

    @Test
    fun aLoneNowHasNothingUpNext() = assertEquals(emptyList(), playlistSections(view(listOf("A")), "A").upNext)

    @Test
    fun aDropBelowARowGoesAfterIt() =
        assertEquals(PlaylistEdit.PlaceAfter("B", "D"), dropEdit(listOf("D", "B", "E"), 1, "C", order))

    @Test
    fun aDropOnTopGoesAfterNow() =
        assertEquals(PlaylistEdit.PlaceAfter("E", "C"), dropEdit(listOf("E", "D"), 0, "C", order))

    @Test
    fun aTopDropUnderAnAiVibeGoesBeforeTheNextRow() =
        assertEquals(PlaylistEdit.PlaceBefore("E", "A"), dropEdit(listOf("E", "A"), 0, "AI Vibe", order))

    @Test
    fun aLoneRowUnderAnAiVibeHasNoEdit() = assertNull(dropEdit(listOf("E"), 0, "AI Vibe", order))
}
